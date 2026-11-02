from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support


def false_positive_rate_per_player_hour(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    player_ids: np.ndarray,
    hours: np.ndarray,
) -> float:
    fp = ((y_true == 0) & (y_pred == 1)).sum()
    player_hours = np.maximum(hours.sum(), 1e-9)
    return float(fp / player_hours)


def eval_binary(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    p, r, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred, labels=[0, 1]).ravel()
    return {
        "precision": float(p),
        "recall": float(r),
        "f1": float(f1),
        "fp": int(fp),
        "tp": int(tp),
        "fn": int(fn),
        "tn": int(tn),
    }


def breakdown_by_column(df: pd.DataFrame, col: str, y_true_col: str, y_pred_col: str) -> pd.DataFrame:
    if col not in df.columns:
        return pd.DataFrame()
    out = []
    for key, g in df.groupby(col):
        m = eval_binary(g[y_true_col].to_numpy(), g[y_pred_col].to_numpy())
        m[col] = key
        out.append(m)
    return pd.DataFrame(out)
