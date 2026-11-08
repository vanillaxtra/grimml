"""Regenerate tests/fixtures/lgbm_parity (model.txt, model_meta.json, cases.json).

Java LgbmModelSelfCheck scores cases.json with the plugin's tree walker; test_lgbm_parity checks lightgbm agrees.
Run from training/: python -m tests.fixtures.make_lgbm_parity
"""
from __future__ import annotations

import json
from pathlib import Path

import lightgbm as lgb
import numpy as np

from grimml_train.features.extract import FEATURE_COLUMNS
from grimml_train.train.lgbm import fit_scaler, transform

OUT = Path(__file__).parent / "lgbm_parity"


def main() -> None:
    rng = np.random.default_rng(7)
    n = 400
    x = rng.normal(size=(n, len(FEATURE_COLUMNS))) * 50 + 10
    x[rng.random(x.shape) < 0.15] = np.nan
    y = ((np.nan_to_num(x[:, 0]) + np.nan_to_num(x[:, 11]) * 0.5 - np.nan_to_num(x[:, 17])) > 10).astype(int)
    scaler = fit_scaler(x)
    xs = transform(scaler, x)
    clf = lgb.LGBMClassifier(n_estimators=25, num_leaves=8, learning_rate=0.1, random_state=7, verbosity=-1)
    clf.fit(xs, y)
    OUT.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(OUT / "model.txt"))
    meta = {
        "feature_names": FEATURE_COLUMNS,
        "threshold": 0.5,
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "format": "lightgbm_txt",
    }
    (OUT / "model_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    probe = x[:24].copy()
    probe[0, :] = np.nan
    probe[1, :] = 0.0
    proba = clf.predict_proba(transform(scaler, probe))[:, 1]
    cases = [
        {"features": {c: (None if not np.isfinite(v) else float(v)) for c, v in zip(FEATURE_COLUMNS, row)}, "proba": float(p)}
        for row, p in zip(probe, proba)
    ]
    (OUT / "cases.json").write_text(json.dumps(cases, indent=1), encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
