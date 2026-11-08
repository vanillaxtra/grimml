from __future__ import annotations

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import f1_score, recall_score, roc_auc_score

from grimml_train.split.splits import grouped_split
from grimml_train.train.families import AIM_COLUMNS, FAMILIES, MACRO_COLUMNS
from grimml_train.train.lgbm import fit_scaler, transform

FAMILY_COLUMNS = {"aim": AIM_COLUMNS, "macro": MACRO_COLUMNS}


def _matrix(df: pd.DataFrame, cols: list[str]) -> np.ndarray:
    x = np.full((len(df), len(cols)), np.nan)
    for i, c in enumerate(cols):
        if c in df.columns:
            x[:, i] = pd.to_numeric(df[c], errors="coerce").to_numpy(dtype=np.float64)
    return x


def family_model(df: pd.DataFrame, family: str, seed: int) -> dict:
    """Binary model: LEGIT_CONFIRMED vs CHEAT of one family, on that family's feature view."""
    cols = FAMILY_COLUMNS[family]
    work = df[(df["family"] == "legit") | (df["family"] == family)]
    if work["family"].nunique() < 2:
        return {"skipped": f"need legit and {family} windows"}
    try:
        train, test = grouped_split(work, seed=seed)
    except ValueError as e:
        return {"skipped": str(e)}
    if train["family"].nunique() < 2 or test["family"].nunique() < 2:
        return {"skipped": "split left one class only"}
    xtr = _matrix(train, cols)
    scaler = fit_scaler(xtr)
    clf = lgb.LGBMClassifier(n_estimators=200, learning_rate=0.05, num_leaves=31, class_weight="balanced",
                             random_state=seed, verbosity=-1)
    clf.fit(transform(scaler, xtr), (train["family"] == family).astype(int))
    proba = clf.predict_proba(transform(scaler, _matrix(test, cols)))[:, 1]
    y = (test["family"] == family).astype(int).to_numpy()
    top = np.argsort(clf.feature_importances_)[::-1][:8]
    return {
        "auc": float(roc_auc_score(y, proba)),
        "recall_at_0.5": float(recall_score(y, proba >= 0.5, zero_division=0)),
        "n_test": int(len(test)),
        "columns": cols,
        "top_features": [cols[i] for i in top],
    }


def multitask_model(df: pd.DataFrame, seed: int, cols: list[str]) -> dict:
    """One multiclass model over legit/aim/macro/other: the cheat head is 1 - P(legit), the family head
    says which kind. Reported next to the binary model, not exported."""
    work = df[df["family"].isin(FAMILIES)]
    present = [f for f in FAMILIES if (work["family"] == f).any()]
    if len(present) < 2 or "legit" not in present:
        return {"skipped": "need legit plus at least one cheat family"}
    try:
        train, test = grouped_split(work, seed=seed)
    except ValueError as e:
        return {"skipped": str(e)}
    classes = {f: i for i, f in enumerate(present)}
    xtr = _matrix(train, cols)
    scaler = fit_scaler(xtr)
    clf = lgb.LGBMClassifier(objective="multiclass", n_estimators=200, learning_rate=0.05, num_leaves=31,
                             class_weight="balanced", random_state=seed, verbosity=-1)
    clf.fit(transform(scaler, xtr), train["family"].map(classes))
    proba = clf.predict_proba(transform(scaler, _matrix(test, cols)))
    y = test["family"].map(classes).to_numpy()
    pred = np.asarray(clf.classes_)[proba.argmax(axis=1)]
    legit_col = list(clf.classes_).index(classes["legit"]) if classes["legit"] in clf.classes_ else None
    cheat_auc = float("nan")
    if legit_col is not None and len(set(y == classes["legit"])) == 2:
        cheat_auc = float(roc_auc_score((y != classes["legit"]).astype(int), 1.0 - proba[:, legit_col]))
    return {
        "classes": present,
        "macro_f1": float(f1_score(y, pred, average="macro", labels=list(classes.values()), zero_division=0)),
        "per_class_recall": {
            f: float(recall_score(y == i, pred == i, zero_division=0)) for f, i in classes.items()
        },
        "cheat_head_auc": cheat_auc,
        "n_test": int(len(test)),
    }
