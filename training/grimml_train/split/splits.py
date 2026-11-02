from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupShuffleSplit

from grimml_train.labels.join import TEST_FORBIDDEN_SOURCES

WEAK = TEST_FORBIDDEN_SOURCES


def assert_not_synthetic(df: pd.DataFrame, source_col: str = "source") -> None:
    if df is None or df.empty:
        return
    if source_col not in df.columns:
        df = df.copy()
        df[source_col] = "real"
    if (df[source_col] == "synthetic").any():
        raise ValueError("synthetic data must never appear in a test split")


def assert_no_leakage(train: pd.DataFrame, test: pd.DataFrame) -> None:
    for col in ("anon_player_id", "session_id", "fight_id", "window_key"):
        if col not in train.columns or col not in test.columns:
            continue
        a = set(train[col].dropna().astype(str))
        b = set(test[col].dropna().astype(str))
        overlap = a & b
        if overlap:
            raise ValueError(f"{col} spans train and test: {list(overlap)[:8]}")


def assert_test_sources(test: pd.DataFrame) -> None:
    if "label_source" in test.columns:
        bad = test[test["label_source"].isin(WEAK)]
        if len(bad):
            raise ValueError("grim-weak/ban-backfill must not appear in the test set")
    if "label" in test.columns and (test["label"] == "UNLABELED").any():
        raise ValueError("UNLABELED must not appear in the test set")


def require_operator_for_cheat(df: pd.DataFrame) -> None:
    cheat = df[df["label"] == "CHEAT"] if "label" in df.columns else df.iloc[0:0]
    if cheat.empty:
        return
    if "operator" not in df.columns or cheat["operator"].isna().any() or (cheat["operator"].astype(str) == "").any():
        raise ValueError("cheat data is missing operator (roster.csv operator or marker setting)")


def _groups(df: pd.DataFrame, group_col: str) -> pd.Series:
    return df[group_col].astype(str)


def grouped_split(
    df: pd.DataFrame,
    group_col: str = "anon_player_id",
    test_size: float = 0.2,
    seed: int = 42,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    if df.empty:
        return df.copy(), df.copy()
    work = df.copy()
    if "source" not in work.columns:
        work["source"] = "real"
    test_pool = work[work["source"] != "synthetic"]
    if "label" in test_pool.columns:
        test_pool = test_pool[test_pool["label"].isin(["CHEAT", "LEGIT_CONFIRMED"])]
    if "label_source" in test_pool.columns:
        test_pool = test_pool[~test_pool["label_source"].isin(WEAK)]
    if test_pool.empty:
        raise ValueError("no confirmed labels available for a test split")
    gss = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    idx = np.arange(len(test_pool))
    tr_rel, te_rel = next(gss.split(idx, groups=_groups(test_pool, group_col)))
    test = test_pool.iloc[te_rel].copy()
    test_ids = set(test[group_col].astype(str))
    train = work[~work[group_col].astype(str).isin(test_ids)].copy()
    assert_not_synthetic(test)
    assert_test_sources(test)
    assert_no_leakage(train, test)
    return train, test


def grouped_train_test_split(
    df: pd.DataFrame,
    group_col: str,
    test_size: float = 0.2,
    seed: int = 42,
    confirmed_only_test: bool = True,
) -> tuple[np.ndarray, np.ndarray]:
    train, test = grouped_split(df, group_col=group_col, test_size=test_size, seed=seed)
    tr = df.index.get_indexer(train.index)
    te = df.index.get_indexer(test.index)
    return tr[tr >= 0], te[te >= 0]


def leave_one_out(df: pd.DataFrame, col: str) -> list[tuple[str, pd.DataFrame, pd.DataFrame]]:
    if col not in df.columns:
        return []
    out = []
    for val in sorted(df[col].dropna().astype(str).unique()):
        test = df[df[col].astype(str) == val].copy()
        train = df[df[col].astype(str) != val].copy()
        if "label_source" in test.columns:
            test = test[~test["label_source"].isin(WEAK)]
        if test.empty or train.empty:
            continue
        assert_not_synthetic(test)
        assert_test_sources(test)
        assert_no_leakage(train, test)
        out.append((val, train, test))
    return out
