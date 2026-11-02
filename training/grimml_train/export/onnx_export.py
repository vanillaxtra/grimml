from __future__ import annotations

import json
from pathlib import Path

import pandas as pd

from grimml_train.train.lgbm import _prep_xy


def export_bundle(clf, feat: pd.DataFrame, labels: pd.DataFrame, out_dir: Path, threshold: float = 0.5) -> None:
    x, _y, feature_names, scaler = _prep_xy(feat, labels)
    out_dir.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(out_dir / "model.txt"))
    meta = {
        "feature_names": feature_names,
        "threshold": threshold,
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "format": "lightgbm_txt",
    }
    (out_dir / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    sample = x[:1].astype("float32")
    prob = float(clf.predict_proba(sample)[:, 1][0])
    (out_dir / "parity_sample_prob.json").write_text(json.dumps({"prob": prob}), encoding="utf-8")
    try:
        from onnxmltools.convert import convert_lightgbm
        from skl2onnx.common.data_types import FloatTensorType

        onnx = convert_lightgbm(clf, initial_types=[("input", FloatTensorType([None, len(feature_names)]))])
        with open(out_dir / "model.onnx", "wb") as f:
            f.write(onnx.SerializeToString())
        meta["onnx"] = True
        (out_dir / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    except Exception as e:
        (out_dir / "onnx_skipped.txt").write_text(str(e), encoding="utf-8")
