from __future__ import annotations

from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import roc_curve
from sklearn.preprocessing import StandardScaler

from grimml_train.features.extract import FEATURE_COLUMNS
from grimml_train.features.forbidden import assert_no_forbidden_features

NEG = {"LEGIT_CONFIRMED"}
POS = {"CHEAT"}


def _xy(df: pd.DataFrame, cols: list[str] | None = None) -> tuple[np.ndarray, np.ndarray, list[str]]:
    cols = cols or [c for c in FEATURE_COLUMNS if c in df.columns]
    assert_no_forbidden_features(cols, allow_grim=False)
    work = df[df["label"].isin(POS | NEG)].copy() if "label" in df.columns else df
    if work.empty:
        raise ValueError("no CHEAT/LEGIT_CONFIRMED rows")
    if (work["label"] == "UNLABELED").any():
        raise ValueError("UNLABELED must not be used as a class")
    y = (work["label"] == "CHEAT").astype(int).to_numpy()
    x = work[cols].to_numpy(dtype=np.float64)
    return x, y, cols


def fit_scaler(x_train: np.ndarray) -> StandardScaler:
    scaler = StandardScaler()
    mean = np.nanmean(x_train, axis=0)
    std = np.nanstd(x_train, axis=0)
    std = np.where(std == 0, 1.0, std)
    scaler.mean_ = np.where(np.isfinite(mean), mean, 0.0)
    scaler.scale_ = std
    scaler.var_ = std ** 2
    scaler.n_features_in_ = x_train.shape[1]
    scaler.n_samples_seen_ = x_train.shape[0]
    return scaler


def transform(scaler: StandardScaler, x: np.ndarray) -> np.ndarray:
    # GML1 missing fields stay NaN; LightGBM consumes them. never fill with 0.
    out = (x - scaler.mean_) / scaler.scale_
    return np.where(np.isfinite(x), out, np.nan)


def train_fold(train: pd.DataFrame, seed: int = 42) -> tuple[lgb.LGBMClassifier, StandardScaler, list[str]]:
    x, y, cols = _xy(train)
    scaler = fit_scaler(x)
    xs = transform(scaler, x)
    clf = lgb.LGBMClassifier(
        n_estimators=200,
        learning_rate=0.05,
        num_leaves=31,
        class_weight="balanced",
        random_state=seed,
        verbosity=-1,
    )
    clf.fit(xs, y)
    return clf, scaler, cols


def threshold_on_val(
    clf: lgb.LGBMClassifier,
    scaler: StandardScaler,
    val: pd.DataFrame,
    cols: list[str],
    max_fpr: float = 0.01,
) -> float:
    x, y, _ = _xy(val, cols)
    if y.size == 0 or y.max() == y.min():
        return 0.5
    proba = clf.predict_proba(transform(scaler, x))[:, 1]
    fpr, _tpr, thr = roc_curve(y, proba)
    ok = np.where(fpr <= max_fpr)[0]
    if ok.size == 0:
        return float(thr[np.argmin(fpr)])
    return float(thr[ok[-1]])


def train_baseline(feat: pd.DataFrame, labels: pd.DataFrame, seed: int = 42) -> lgb.LGBMClassifier:
    merged = feat.merge(labels, on="window_key", how="inner", suffixes=("", "_y"))
    clf, _, _ = train_fold(merged, seed)
    return clf


def save_model(clf: lgb.LGBMClassifier, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(path))
