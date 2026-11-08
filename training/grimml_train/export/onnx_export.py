from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.features.extract import JAVA_FEATURE_COLUMNS
from grimml_train.features.forbidden import assert_no_forbidden_features
from grimml_train.train.lgbm import _xy, transform

PARITY_ATOL = 1e-5


def export_bundle(clf, scaler, cols: list[str], feat: pd.DataFrame, out_dir: Path, threshold: float) -> dict:
    """model.txt + model_meta.json are what the plugin loads (pure java tree walker). model.onnx is
    exported for other runtimes and must match lightgbm on every row, NaNs included, or export fails."""
    assert_no_forbidden_features(cols, allow_grim=False)
    missing = [c for c in cols if c not in JAVA_FEATURE_COLUMNS]
    if missing:
        raise ValueError(f"plugin cannot compute {missing}; alert models must use FEATURE_COLUMNS only")
    out_dir.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(out_dir / "model.txt"))
    meta = {
        "feature_names": cols,
        "threshold": threshold,
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "format": "lightgbm_txt",
        "onnx": False,
    }
    x, _y, _ = _xy(feat, cols)
    xs = transform(scaler, x)
    py = clf.predict_proba(xs)[:, 1]
    report = {"rows": int(len(xs)), "python_mean_prob": float(py.mean()) if len(py) else None}
    try:
        from onnxmltools.convert import convert_lightgbm
        from onnxmltools.convert.common.data_types import FloatTensorType
        import onnxruntime as ort
    except ImportError as e:
        report["onnx"] = f"skipped: {e}"
    else:
        onnx = convert_lightgbm(clf, initial_types=[("input", FloatTensorType([None, len(cols)]))], zipmap=False)
        onnx_path = out_dir / "model.onnx"
        onnx_path.write_bytes(onnx.SerializeToString())
        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        outputs = sess.run(None, {sess.get_inputs()[0].name: xs.astype(np.float32)})
        probs = next(o for o in outputs if getattr(o, "ndim", 0) == 2)
        ox = probs[:, 1].astype(np.float64)
        diff = np.abs(ox - py)
        # onnx runs float32 thresholds; a row that sits exactly on a split can flip, so count those separately
        report.update({"onnx": True, "max_abs_diff": float(diff.max()) if len(diff) else 0.0,
                       "rows_over_atol": int((diff > PARITY_ATOL).sum())})
        if len(diff) and report["rows_over_atol"] > max(1, len(diff) // 1000):
            (out_dir / "onnx_parity.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            raise AssertionError(f"onnx parity failed: {report['rows_over_atol']} rows over {PARITY_ATOL}, max {report['max_abs_diff']}")
        meta["onnx"] = True
    (out_dir / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    (out_dir / "onnx_parity.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report
