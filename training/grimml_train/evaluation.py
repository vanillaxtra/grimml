"""Model fitting and held-out evaluation.

The unit of evaluation is never a single window: a whole cheat session and a whole legit chunk are held out
together (leave-one-segment-out), and the alert line is set per FIGHT, like the detector judges players.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from grimml_train.catalog import CHEAT, LEGIT

LGBM_PARAMS = dict(
    n_estimators=150, learning_rate=0.05, num_leaves=8, min_child_samples=5,
    subsample=0.8, subsample_freq=1, colsample_bytree=0.8, class_weight="balanced", verbosity=-1,
)
FIGHT_MIN_WINDOWS = 10   # a fight shorter than this is never judged (detector: fight_min_windows)
LEAK_AUC = 0.75          # shortcut-only AUC from which the labels count as leaking through the setup


def fit(train: pd.DataFrame, cols: list[str], seed: int):
    import lightgbm as lgb

    clf = lgb.LGBMClassifier(random_state=seed, **LGBM_PARAMS)
    clf.fit(train[cols].to_numpy(dtype=float), (train["label"] == CHEAT).astype(int).to_numpy())
    return clf


def score(clf, df: pd.DataFrame, cols: list[str]) -> np.ndarray:
    return clf.predict_proba(df[cols].to_numpy(dtype=float))[:, 1]


def auc(label: np.ndarray, scores: np.ndarray) -> float:
    from sklearn.metrics import roc_auc_score

    y = label == CHEAT
    if y.all() or (~y).all():
        return float("nan")
    return float(roc_auc_score(y, scores))


def loso(cheat: pd.DataFrame, legit: pd.DataFrame, cols: list[str], seed: int) -> pd.DataFrame | None:
    """Out-of-fold scores: each fold holds out one whole cheat session and one whole legit chunk."""
    segs, chunks = sorted(cheat["block"].unique()), sorted(legit["block"].unique())
    if len(segs) < 2 or len(chunks) < 2:
        return None
    rows = []
    for j in range(max(len(segs), len(chunks))):
        s, c = segs[j % len(segs)], chunks[j % len(chunks)]
        te = pd.concat([cheat[cheat["block"] == s], legit[legit["block"] == c]])
        tr = pd.concat([cheat[cheat["block"] != s], legit[legit["block"] != c]])
        te = te.assign(score=score(fit(tr, cols, seed + j), te, cols))
        te["player"] = te["anon_player_id"].astype(str) if "anon_player_id" in te.columns else ""
        rows.append(te[["window_key", "label", "block", "mark_id", "player", "score"]])
    return (
        pd.concat(rows)
        .groupby("window_key", as_index=False)
        .agg(label=("label", "first"), block=("block", "first"), mark_id=("mark_id", "first"),
             player=("player", "first"), score=("score", "mean"))
    )


def block_ci(oof: pd.DataFrame, n: int = 200, seed: int = 0) -> tuple[float, float]:
    """95% AUC interval from resampling whole blocks (windows of one block are not independent)."""
    rng = np.random.default_rng(seed)
    groups = {b: (g["label"].iloc[0], g["score"].to_numpy()) for b, g in oof.groupby("block")}
    cb = [b for b, (lab, _) in groups.items() if lab == CHEAT]
    lb = [b for b, (lab, _) in groups.items() if lab != CHEAT]
    vals = []
    for _ in range(n):
        pick = list(rng.choice(cb, len(cb))) + list(rng.choice(lb, len(lb)))
        sc = np.concatenate([groups[b][1] for b in pick])
        lab = np.concatenate([[groups[b][0]] * len(groups[b][1]) for b in pick])
        a = auc(lab, sc)
        if a == a:
            vals.append(a)
    if not vals:
        return float("nan"), float("nan")
    return float(np.quantile(vals, 0.025)), float(np.quantile(vals, 0.975))


def operating_points(oof: pd.DataFrame) -> dict:
    """Window thresholds that flag 5% and 1% of legit windows, and the cheat recall at each."""
    legit = oof.loc[oof["label"] == LEGIT, "score"].to_numpy()
    cheat = oof.loc[oof["label"] == CHEAT, "score"].to_numpy()
    out = {}
    for f in (0.05, 0.01):
        thr = float(np.quantile(legit, 1 - f))
        out[f"fpr{int(f * 100)}"] = {"thr": thr, "recall": float((cheat >= thr).mean())}
    return out


def fight_scores(oof: pd.DataFrame, ops: dict) -> dict:
    """Judge whole fights. Unit = one cheat session, one legit chunk or one bulk player; its flag share is the
    fraction of its windows above the 5%-of-legit-windows threshold. The line flags ~5% of legit units."""
    thr = ops["fpr5"]["thr"]
    o = oof.copy()
    bulk = o["block"].astype(str).str.startswith("bulk")
    o["unit"] = np.where(bulk, o["block"].astype(str) + "/" + o["player"], o["block"].astype(str))
    g = o.groupby("unit").agg(label=("label", "first"), mark_id=("mark_id", "first"), n=("score", "size"),
                              share=("score", lambda s: float((s >= thr).mean())))
    g = g[g["n"] >= FIGHT_MIN_WINDOWS]
    leg, che = g[g["label"] == LEGIT], g[g["label"] == CHEAT]
    if len(leg) < 5 or che.empty:
        return {"skipped": f"needs >=5 legit and >=1 cheat units of {FIGHT_MIN_WINDOWS}+ windows"}
    line = float(np.quantile(leg["share"], 0.95))
    return {
        "legit_units": int(len(leg)), "legit_share_median": float(leg["share"].median()),
        "legit_share_p95": line, "legit_share_max": float(leg["share"].max()),
        "cheat_units": int(len(che)),
        "cheat_caught": int((che["share"] > line).sum()),
        "cheat": [{"mark_id": str(r.mark_id), "windows": int(r.n), "share": float(r.share)} for r in che.itertuples()],
        # the legit fights the model likes least: the first place to look for false positives
        "legit_top": [{"unit": str(u), "mark_id": str(r.mark_id), "windows": int(r.n), "share": float(r.share)}
                      for u, r in leg.sort_values("share", ascending=False).head(3).iterrows()],
    }


def per_segment(oof: pd.DataFrame, ops: dict, sessions: pd.DataFrame) -> list[dict]:
    settings = sessions.set_index("mark_id")["settings"]
    rows = []
    for b, g in oof[oof["label"] == CHEAT].groupby("block"):
        rows.append({
            "mark_id": b, "settings": str(settings.get(b, "")), "windows": int(len(g)),
            "recall_at_5pct_fpr": float((g["score"] >= ops["fpr5"]["thr"]).mean()),
            "recall_at_1pct_fpr": float((g["score"] >= ops["fpr1"]["thr"]).mean()),
            "median_score": float(g["score"].median()),
        })
    return rows


def transfer(train_cheat: pd.DataFrame, legit: pd.DataFrame, other_cheat: pd.DataFrame, cols: list[str],
             seed: int) -> dict | None:
    """Train on train_cheat, score cheat windows from other clients; legit is held out chunk by chunk."""
    chunks = sorted(legit["block"].unique())
    if len(chunks) < 2 or other_cheat.empty or train_cheat.empty:
        return None
    leg, cheat = [], []
    for j, c in enumerate(chunks):
        clf = fit(pd.concat([train_cheat, legit[legit["block"] != c]]), cols, seed + j)
        leg.append(score(clf, legit[legit["block"] == c], cols))
        cheat.append(pd.Series(score(clf, other_cheat, cols), index=other_cheat.index))
    legit_all = np.concatenate(leg)
    mean_c = pd.concat(cheat, axis=1).mean(axis=1)
    res = {"auc": auc(np.r_[[LEGIT] * len(legit_all), [CHEAT] * len(mean_c)], np.r_[legit_all, mean_c.to_numpy()])}
    for f in (0.05, 0.01):
        thr = float(np.quantile(legit_all, 1 - f))
        res[f"recall_fpr{int(f * 100)}"] = {
            str(cl): float((s >= thr).mean()) for cl, s in mean_c.groupby(other_cheat.loc[mean_c.index, "id_client"])
        }
    res["windows_by_client"] = {str(k): int(v) for k, v in other_cheat["id_client"].value_counts().items()}
    return res


def verdict(core_auc: float, short_auc: float, n_seg: int, n_chunks: int) -> str:
    if core_auc != core_auc:
        return "not enough segments/chunks"
    if short_auc == short_auc and short_auc >= LEAK_AUC:
        return "LEAK RISK: window length/ping/counts alone separate the labels"
    if core_auc < 0.6:
        return "no clear separation (features, or the setting is too subtle)"
    note = "separates on held-out segments"
    if n_seg < 3 or n_chunks < 3:
        note += " (very few blocks, treat as a hint)"
    return note + "; single operator"


def evaluate_module(name: str, cheat: pd.DataFrame, legit: pd.DataFrame, sessions: pd.DataFrame,
                    sets: dict[str, list[str]], seed: int) -> tuple[dict, object | None]:
    """Held-out results for one module, and the final model fitted on all its windows (None if skipped)."""
    res = {
        "module": name,
        "cheat_windows": int(len(cheat)), "cheat_segments": int(cheat["block"].nunique()),
        "legit_windows": int(len(legit)), "legit_chunks": int(legit["block"].nunique()),
    }
    oofs = {}
    for set_name, cols in sets.items():
        oof = loso(cheat, legit, cols, seed)
        if oof is None:
            res["skipped"] = "needs at least 2 cheat segments and 2 legit chunks"
            return res, None
        lo, hi = block_ci(oof)
        res[set_name] = {"auc": auc(oof["label"].to_numpy(), oof["score"].to_numpy()), "ci": [lo, hi],
                         "n_features": len(cols)}
        oofs[set_name] = oof
    ops = operating_points(oofs["core"])
    res["core_operating_points"] = ops
    res["segments"] = per_segment(oofs["core"], ops, sessions)
    res["fights"] = fight_scores(oofs["core"], ops)
    # the published model: fitted on everything; its thresholds come from the held-out scores above
    clf = fit(pd.concat([cheat, legit]), sets["core"], seed)
    gain = clf.booster_.feature_importance("gain")
    res["top_core_features"] = [(k, float(v)) for k, v in sorted(zip(sets["core"], gain), key=lambda x: -x[1])[:5]]
    res["verdict"] = verdict(res["core"]["auc"], res["shortcut"]["auc"], res["cheat_segments"], res["legit_chunks"])
    return res, clf
