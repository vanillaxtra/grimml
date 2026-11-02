from __future__ import annotations

from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.preprocessing import StandardScaler

from grimml_train.features.extract import FEATURE_COLUMNS, GRIM_FEATURE_PREFIXES


def _prep_xy(feat: pd.DataFrame, labels: pd.DataFrame, label_col: str = "label"):
    if label_col in feat.columns and "window_key" in feat.columns:
        merged = feat.copy()
        if label_col not in merged.columns:
            merged = feat.merge(labels[["window_key", label_col]], on="window_key", how="inner")
        elif labels is not None and "window_key" in labels.columns and label_col in labels.columns:
            if feat[label_col].isna().any():
                merged = feat.drop(columns=[label_col]).merge(labels[["window_key", label_col]], on="window_key", how="inner")
            else:
                merged = feat
    else:
        merged = feat.merge(labels[["window_key", label_col]], on="window_key", how="inner")
    merged = merged[merged[label_col].isin(["CHEAT", "LEGIT_CONFIRMED"])]
    if merged.empty:
        raise ValueError("no staff-labeled CHEAT/LEGIT_CONFIRMED rows for training")
    y = (merged[label_col] == "CHEAT").astype(int).to_numpy()
    cols = [c for c in FEATURE_COLUMNS if c in merged.columns]
    cols = [c for c in cols if not any(c.startswith(p) for p in GRIM_FEATURE_PREFIXES)]
    x = merged[cols].to_numpy(dtype=np.float64)
    x = np.where(np.isfinite(x), x, 0.0)
    scaler = StandardScaler()
    x = scaler.fit_transform(x)
    return x, y, cols, scaler


def train_baseline(feat: pd.DataFrame, labels: pd.DataFrame, seed: int = 42) -> lgb.LGBMClassifier:
    x, y, _, _ = _prep_xy(feat, labels)
    clf = lgb.LGBMClassifier(
        n_estimators=200,
        learning_rate=0.05,
        num_leaves=31,
        class_weight="balanced",
        random_state=seed,
        verbosity=-1,
    )
    clf.fit(x, y)
    return clf


def save_model(clf: lgb.LGBMClassifier, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(path))
