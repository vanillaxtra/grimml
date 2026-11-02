from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.preprocessing import LabelEncoder

from grimml_train.eval.metrics import eval_binary
from grimml_train.features.forbidden import CONFOUND_WATCH
from grimml_train.train.lgbm import _xy, transform


def window_hours(df: pd.DataFrame) -> np.ndarray:
    if "window_start_nanos" in df.columns and "window_end_nanos" in df.columns:
        ns = pd.to_numeric(df["window_end_nanos"], errors="coerce") - pd.to_numeric(df["window_start_nanos"], errors="coerce")
        hrs = ns.to_numpy(dtype=np.float64) / 3.6e12
        hrs = np.where(np.isfinite(hrs) & (hrs > 0), hrs, 7 / 3600)
        return hrs
    return np.full(len(df), 7 / 3600)


def fp_per_1000_player_hours(y_true: np.ndarray, y_pred: np.ndarray, hours: np.ndarray) -> float:
    fp = ((y_true == 0) & (y_pred == 1)).sum()
    legit_hours = hours[y_true == 0].sum()
    if legit_hours <= 0:
        return float("nan")
    return float(fp / legit_hours * 1000)


def player_aggregate(proba: np.ndarray, players: np.ndarray, n_alert: int = 3, decay: float = 0.9) -> dict[str, float]:
    scores: dict = {}
    for p, s in zip(players, proba):
        prev = scores.get(p, 0.0) * decay
        scores[p] = prev + float(s)
    flagged = {k: v for k, v in scores.items() if v >= n_alert * 0.5}
    return {"players": len(scores), "flagged": len(flagged)}


def bootstrap_by_player(y_true, y_pred, players, fn, n=200, seed=42) -> tuple[float, float, float]:
    rng = np.random.default_rng(seed)
    uniq = np.unique(players)
    stats = []
    for _ in range(n):
        samp = rng.choice(uniq, size=len(uniq), replace=True)
        mask = np.isin(players, samp)
        if mask.sum() == 0:
            continue
        stats.append(fn(y_true[mask], y_pred[mask]))
    if not stats:
        return float("nan"), float("nan"), float("nan")
    a = np.array(stats, dtype=float)
    return float(np.mean(a)), float(np.quantile(a, 0.025)), float(np.quantile(a, 0.975))


def confound_probe(legit: pd.DataFrame, feature_cols: list[str], target: str) -> float:
    if target not in legit.columns or len(legit) < 8:
        return float("nan")
    yraw = legit[target]
    if yraw.nunique() < 2:
        return float("nan")
    x = legit[feature_cols].to_numpy(dtype=np.float64)
    x = np.where(np.isfinite(x), x, 0.0)
    if yraw.dtype == object or str(yraw.dtype).startswith("string"):
        y = LabelEncoder().fit_transform(yraw.astype(str))
    else:
        y = yraw.astype(int).to_numpy()
        if y.max() > 1:
            y = LabelEncoder().fit_transform(y)
    if len(set(y)) < 2:
        return float("nan")
    clf = LogisticRegression(max_iter=200, class_weight="balanced")
    clf.fit(x, y)
    if len(set(y)) == 2:
        proba = clf.predict_proba(x)[:, 1]
        return float(roc_auc_score(y, proba))
    proba = clf.predict_proba(x)
    return float(roc_auc_score(y, proba, multi_class="ovr"))


def warn_confound_importance(clf, cols: list[str]) -> list[str]:
    if not hasattr(clf, "feature_importances_"):
        return []
    order = np.argsort(clf.feature_importances_)[::-1]
    top = [cols[i] for i in order[:10] if i < len(cols)]
    return [c for c in top if c in CONFOUND_WATCH]


def evaluate(
    clf,
    scaler,
    cols: list[str],
    test: pd.DataFrame,
    threshold: float,
) -> dict:
    x, y, _ = _xy(test, cols)
    proba = clf.predict_proba(transform(scaler, x))[:, 1]
    pred = (proba >= threshold).astype(int)
    hours = window_hours(test)
    players = test["anon_player_id"].astype(str).to_numpy() if "anon_player_id" in test.columns else np.arange(len(test))
    fp1000 = fp_per_1000_player_hours(y, pred, hours)
    mean_fp, lo, hi = bootstrap_by_player(
        y, pred, players, lambda yt, yp: fp_per_1000_player_hours(yt, yp, hours[: len(yt)] * 0 + hours.mean())
    )
    rep = {
        "fp_per_1000_player_hours": fp1000,
        "fp_per_1000_ci": [lo, hi],
        "window_metrics": eval_binary(y, pred),
        "n_windows": int(len(test)),
        "n_players": int(pd.Series(players).nunique()),
        "threshold": threshold,
        "player_agg": player_aggregate(proba, players),
        "confound_top10": warn_confound_importance(clf, cols),
    }
    for col in ("mark_module_id", "mark_client_id", "client_brand", "ping_ms"):
        if col not in test.columns:
            continue
    return rep
