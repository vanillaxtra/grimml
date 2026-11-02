from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.features.forbidden import assert_no_forbidden_features
from grimml_train.train.lgbm import _xy, transform


def export_bundle(clf, scaler, cols: list[str], feat: pd.DataFrame, out_dir: Path, threshold: float) -> dict:
    assert_no_forbidden_features(cols, allow_grim=False)
    out_dir.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(out_dir / "model.txt"))
    meta = {
        "feature_names": cols,
        "threshold": threshold,
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "format": "lightgbm_txt",
    }
    (out_dir / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    x, _y, _ = _xy(feat, cols)
    xs = transform(scaler, x[:1])
    xs0 = np.where(np.isfinite(xs), xs, 0.0)
    py_prob = float(clf.predict_proba(xs0)[:, 1][0])
    (out_dir / "parity_sample_prob.json").write_text(json.dumps({"prob": py_prob}), encoding="utf-8")
    onnx_ok = False
    try:
        from onnxmltools.convert import convert_lightgbm
        from skl2onnx.common.data_types import FloatTensorType
        import onnxruntime as ort

        onnx = convert_lightgbm(clf, initial_types=[("input", FloatTensorType([None, len(cols)]))])
        onnx_path = out_dir / "model.onnx"
        with open(onnx_path, "wb") as f:
            f.write(onnx.SerializeToString())
        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        name = sess.get_inputs()[0].name
        onnx_out = sess.run(None, {name: xs0.astype(np.float32)})[0]
        onnx_prob = float(onnx_out.reshape(-1)[-1] if onnx_out.ndim else onnx_out)
        if abs(onnx_prob - py_prob) > 1e-3 and abs(float(onnx_out.reshape(-1)[0]) - py_prob) > 1e-3:
            # compare first output channel as probability if shape is [n,2]
            if onnx_out.ndim == 2 and onnx_out.shape[1] >= 2:
                onnx_prob = float(onnx_out[0, 1])
        if abs(onnx_prob - py_prob) > 5e-2:
            raise AssertionError(f"onnx parity {onnx_prob} vs python {py_prob}")
        onnx_ok = True
        meta["onnx"] = True
        (out_dir / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    except Exception as e:
        (out_dir / "onnx_skipped.txt").write_text(str(e), encoding="utf-8")
    return {"python_prob": py_prob, "onnx": onnx_ok}
