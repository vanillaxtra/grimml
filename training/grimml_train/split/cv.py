from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.model_selection import StratifiedGroupKFold

from grimml_train.split.splits import WEAK, assert_no_leakage, assert_test_sources, grouped_split


def grouped_cv(df: pd.DataFrame, k: int, seed: int, train_fn, eval_fn, group_col: str = "anon_player_id") -> dict:
    """k-fold over players: every confirmed window is tested once, a player never spans train and test.
    train_fn(train_df) -> model, eval_fn(model, inner_val_df, test_df) -> metrics dict."""
    pool = df[df["label"].isin(["CHEAT", "LEGIT_CONFIRMED"])] if "label" in df.columns else df
    if "label_source" in pool.columns:
        pool = pool[~pool["label_source"].isin(WEAK)]
    groups = pool[group_col].astype(str)
    n_groups = groups.nunique()
    k = min(k, n_groups)
    if k < 2:
        return {"folds": [], "skipped": f"need >= 2 players, have {n_groups}"}
    y = (pool["label"] == "CHEAT").astype(int)
    splitter = StratifiedGroupKFold(n_splits=k, shuffle=True, random_state=seed)
    folds = []
    for i, (_tr, te) in enumerate(splitter.split(pool, y, groups)):
        test = pool.iloc[te]
        test_ids = set(test[group_col].astype(str))
        train = df[~df[group_col].astype(str).isin(test_ids)]
        assert_test_sources(test)
        assert_no_leakage(train, test)
        try:
            inner, val = grouped_split(train, group_col=group_col, test_size=0.25, seed=seed + i)
            model = train_fn(inner)
            m = eval_fn(model, val, test)
        except ValueError as e:
            folds.append({"fold": i, "skipped": str(e)})
            continue
        m["fold"] = i
        m["n_test"] = int(len(test))
        folds.append(m)
    return {"folds": folds, "summary": summarize(folds)}


def summarize(folds: list[dict]) -> dict:
    out = {}
    for key in ("fp_per_1000_player_hours", "precision", "recall", "f1"):
        vals = []
        for f in folds:
            v = f.get(key, f.get("window_metrics", {}).get(key))
            if v is not None and np.isfinite(v):
                vals.append(float(v))
        if vals:
            out[key] = {"mean": float(np.mean(vals)), "std": float(np.std(vals)), "n": len(vals)}
    return out
