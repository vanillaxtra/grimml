from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupShuffleSplit


def assert_not_synthetic(df: pd.DataFrame, source_col: str = "source") -> None:
    if df.empty:
        return
    if source_col not in df.columns:
        return
    if (df[source_col] == "synthetic").any():
        raise ValueError("synthetic data must never appear in a test split")


def grouped_train_test_split(
    df: pd.DataFrame,
    group_col: str,
    test_size: float = 0.2,
    seed: int = 42,
    confirmed_only_test: bool = True,
) -> tuple[np.ndarray, np.ndarray]:
    if df.empty:
        return np.array([], dtype=int), np.array([], dtype=int)
    work = df
    if confirmed_only_test and "confirmed_label" in df.columns:
        work = df[df["confirmed_label"] | (df["label"] != "UNLABELED")].copy()
        if work.empty:
            work = df
    groups = work[group_col].astype(str)
    gss = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    idx = np.arange(len(work))
    train_i, test_i = next(gss.split(idx, groups=groups))
    return train_i, test_i
