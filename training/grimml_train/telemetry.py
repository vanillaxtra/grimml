"""Telemetry from real servers (uploads copied from the receiver into training/telemetry/telemetry-data).

Nobody labels these players, so the windows are never training data: a model flagging a player here can be right.
They are used for two checks instead:

- the false-flag gate: how many of a server's players a model would flag. Cheaters are a small minority on a normal
  server, so a model that flags a large share of the players there is flagging legit play. `publish` refuses such a
  model unless told otherwise.
- the setup check: a classifier that tries to tell recorded legit windows from real-server windows. If it can, the
  recordings were made in a setup unlike real servers, and its top features say how.

Fights are sampled on the server (every alerted and borderline fight, a matching number of quiet ones, a random
slice), each tagged with its keep chance; shares of fights are weighted by 1/keep to undo that.
"""
from __future__ import annotations

import gzip
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.paths import TELEMETRY_DIR

MIN_WINDOWS = 20        # fights shorter than this are not judged (the plugin needs 10+, longer ones are clearer)
MIN_PLAYERS = 10        # servers with fewer judged players are left out of the gate
MAX_PLAYER_SHARE = 0.08  # a model may flag at most this share of a server's players


@dataclass
class Telemetry:
    features: list[str]                       # column order of X
    X: np.ndarray                             # one row per window, NaN where a server did not send the feature
    windows: pd.DataFrame                     # server, g (fight), p (player hash), t
    fights: pd.DataFrame                      # server, g, p, keep, band (from the fight lines)
    labels: dict[tuple[str, str], str] = field(default_factory=dict)  # (server, player) -> cheat | legit
    bans: set[tuple[str, str]] = field(default_factory=set)            # (server, player) banned on that server
    files: int = 0

    @property
    def empty(self) -> bool:
        return len(self.X) == 0


def files(folder: Path = TELEMETRY_DIR) -> list[Path]:
    return sorted(Path(folder).glob("*/*.jsonl.gz")) if Path(folder).exists() else []


def load(folder: Path = TELEMETRY_DIR) -> Telemetry:
    """Every upload in folder. Servers on other plugin versions send other feature lists; they are lined up by name."""
    paths = files(folder)
    names: list[str] = []
    pos: dict[str, int] = {}
    rows: list[tuple] = []
    fights: list[dict] = []
    labels: dict[tuple[str, str], str] = {}
    bans: set[tuple[str, str]] = set()
    for path in paths:
        server = path.name.split("-")[0]
        header: list[str] = []
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            for line in fh:
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                kind = o.get("type")
                if kind == "header":
                    header = list(o.get("features") or [])
                    for n in header:
                        if n not in pos:
                            pos[n] = len(names)
                            names.append(n)
                elif kind == "window" and header and "g" in o:  # schema 4 uploads had no fight ids
                    rows.append((server, int(o["g"]), str(o["p"]), int(o.get("t", 0)), o.get("a"), o.get("e"),
                                 o["f"], header))
                elif kind == "fight":
                    fights.append({"server": server, "g": int(o["g"]), "p": str(o["p"]),
                                   "keep": float(o.get("keep") or 1.0), "band": o.get("band")})
                elif kind == "label" and o.get("label") in ("cheat", "legit"):
                    labels[(server, str(o["p"]))] = o["label"]
                elif kind == "ban":
                    bans.add((server, str(o["p"])))
    X = np.full((len(rows), len(names)), np.nan)
    for i, (*_, f, header) in enumerate(rows):
        cols = [pos[n] for n in header[:len(f)]]
        X[i, cols] = np.array([np.nan if v is None else v for v in f[:len(cols)]], dtype=float)
    # attack_count / event_count: sent since 1.2.0 telemetry with ban lines, NaN before
    w = pd.DataFrame([r[:6] for r in rows], columns=["server", "g", "p", "t", "attack_count", "event_count"])
    w[["attack_count", "event_count"]] = w[["attack_count", "event_count"]].astype(float)
    fd = pd.DataFrame(fights, columns=["server", "g", "p", "keep", "band"]).drop_duplicates(["server", "g"])
    return Telemetry(names, X, w, fd, labels, bans, len(paths))


def summary(tel: Telemetry) -> str:
    if tel.empty:
        return "no telemetry"
    n = tel.windows.groupby("server").agg(windows=("g", "size"), fights=("g", "nunique"), players=("p", "nunique"))
    return (f"{tel.files} uploads, {len(tel.X)} windows, {n['fights'].sum()} fights, {n['players'].sum()} players "
            f"on {len(n)} servers")


def scores(booster, meta: dict, tel: Telemetry) -> np.ndarray | None:
    """The model's window score for every telemetry window, None if the servers never sent a feature it needs."""
    idx = {n: i for i, n in enumerate(tel.features)}
    missing = [n for n in meta["features"] if n not in idx]
    if missing:
        return None
    return booster.predict(tel.X[:, [idx[n] for n in meta["features"]]])


def flag_shares(window_scores: np.ndarray, threshold: float, line: float, tel: Telemetry,
                min_windows: int = MIN_WINDOWS, min_players: int = MIN_PLAYERS) -> dict:
    """How many fights and players a model with this window threshold and alert line would flag, per server.

    A fight is over the line when at least `line` of its windows score >= threshold (the whole fight at once, so a
    little stricter than the plugin's 4-minute blocks). Players labelled cheat by staff and banned players are left
    out; players labelled legit and flagged are listed, and how many banned players the model flagged."""
    w = tel.windows.assign(flag=window_scores >= threshold)
    per = w.groupby(["server", "g", "p"]).agg(windows=("flag", "size"), share=("flag", "mean")).reset_index()
    per = per[per["windows"] >= min_windows]
    per = per.merge(tel.fights[["server", "g", "keep"]], on=["server", "g"], how="left")
    per["keep"] = per["keep"].fillna(1.0).clip(lower=1e-3)
    key = list(zip(per["server"], per["p"]))
    per = per.assign(label=[tel.labels.get(k) for k in key], banned=[k in tel.bans for k in key],
                     over=per["share"] >= line)
    judged = per[(per["label"] != "cheat") & ~per["banned"]]
    servers = []
    for server, s in judged.groupby("server"):
        players = s.groupby("p")["over"].any()
        servers.append({"server": server[:8], "fights": int(len(s)),
                        "fight_share": float(np.average(s["over"], weights=1 / s["keep"])),
                        "players": int(len(players)), "players_flagged": int(players.sum()),
                        "player_share": float(players.mean()), "gated": len(players) >= min_players})
    legit_flagged = sorted({p[:8] for p in per.loc[(per["label"] == "legit") & per["over"], "p"]})
    gated = [s["player_share"] for s in servers if s["gated"]]
    banned = per[per["banned"]].groupby(["server", "p"])["over"].any()
    return {"banned_players": int(len(banned)), "banned_flagged": int(banned.sum()),"line": round(line, 3), "threshold": round(float(threshold), 4), "min_windows": min_windows,
            "servers": servers, "worst_player_share": max(gated) if gated else None,
            "labelled_legit_flagged": legit_flagged}


def gate(shares: dict, max_player_share: float = MAX_PLAYER_SHARE) -> tuple[bool, str]:
    """(passes, reason). No server big enough counts as a pass, said so in the reason."""
    if shares.get("labelled_legit_flagged"):
        return False, f"flags {len(shares['labelled_legit_flagged'])} players staff labelled legit"
    worst = shares.get("worst_player_share")
    if worst is None:
        return True, f"no server with {MIN_PLAYERS}+ judged players yet"
    if worst > max_player_share:
        return False, f"flags {worst:.0%} of one server's players (limit {max_player_share:.0%})"
    return True, f"flags at most {worst:.0%} of a server's players (limit {max_player_share:.0%})"


def check_model(model_txt: Path, meta: dict, line: float, tel: Telemetry) -> dict:
    """Gate result for one model file at one alert line, for result.json and publish."""
    import lightgbm as lgb

    s = scores(lgb.Booster(model_file=str(model_txt)), meta, tel)
    if s is None:
        return {"skipped": "the servers' plugin does not send every feature this model needs (older version)"}
    out = flag_shares(s, meta["window_threshold_fpr5"], line, tel)
    out["passes"], out["reason"] = gate(out)
    return out


def setup_check(legit: pd.DataFrame, features: list[str], tel: Telemetry, seed: int = 0,
                per_side: int = 20000) -> dict:
    """Can a classifier tell recorded legit windows (grouped by session) from real-server windows (grouped by
    player)? AUC near 0.5 means the recordings look like real servers on these features. Real servers have some
    cheaters too, but far too few to move this."""
    import lightgbm as lgb
    from sklearn.metrics import roc_auc_score
    from sklearn.model_selection import GroupKFold

    idx = {n: i for i, n in enumerate(tel.features)}
    feats = [f for f in features if f in idx and f in legit.columns]
    if tel.empty or legit.empty or len(feats) < 2:
        return {"skipped": "no telemetry or no shared features"}
    rng = np.random.default_rng(seed)
    a = legit.sample(min(len(legit), per_side), random_state=seed)
    b = rng.choice(len(tel.X), min(len(tel.X), per_side), replace=False)
    X = np.vstack([a[feats].to_numpy(dtype=float), tel.X[b][:, [idx[f] for f in feats]]])
    y = np.r_[np.zeros(len(a)), np.ones(len(b))]
    # whole sessions (blocks split one session into chunks, which would leak across folds); bulk players by block
    rec = a["mark_id"].astype(str)
    if "block" in a.columns:
        rec = rec.where(~a["block"].astype(str).str.startswith("bulk:"), a["block"].astype(str))
    groups = np.r_[("rec:" + rec).to_numpy(),
                   ("tel:" + tel.windows["p"].to_numpy()[b]).astype(object)]
    pred = np.zeros(len(y))
    gain = np.zeros(len(feats))
    for tr, te in GroupKFold(n_splits=3).split(X, y, groups):
        m = lgb.LGBMClassifier(n_estimators=150, learning_rate=0.05, num_leaves=15, min_child_samples=50,
                               subsample=0.8, subsample_freq=1, colsample_bytree=0.8, random_state=seed, verbose=-1)
        m.fit(X[tr], y[tr])
        pred[te] = m.predict_proba(X[te])[:, 1]
        gain += m.booster_.feature_importance("gain")
    gain = gain / gain.sum() if gain.sum() else gain
    top = sorted(zip(feats, gain), key=lambda t: -t[1])[:6]
    med = [(f, float(np.nanmedian(X[y == 0, feats.index(f)])), float(np.nanmedian(X[y == 1, feats.index(f)])))
           for f, _ in top]
    return {"auc": float(roc_auc_score(y, pred)), "recorded": int(len(a)), "telemetry": int(len(b)),
            "features": len(feats),
            "top": [{"feature": f, "gain": round(float(g), 3), "recorded_median": round(r, 4),
                     "telemetry_median": round(t, 4)} for (f, g), (_, r, t) in zip(top, med)]}


def lines(r: dict) -> list[str]:
    """Report lines for result.json's 'telemetry' entry."""
    L = []
    g = r.get("gate")
    if g is not None:
        if "skipped" in g:
            L.append(f"   real servers: {g['skipped']}")
        else:
            L.append(f"   real servers at line {g['line']:.0%}: {'PASS' if g['passes'] else 'FAIL'}, {g['reason']}")
            for s in g["servers"]:
                L.append(f"     server {s['server']}  {s['players_flagged']}/{s['players']} players "
                         f"({s['player_share']:.0%}), {s['fight_share']:.1%} of fights"
                         + ("" if s["gated"] else "  (too few players to count)"))
            if g.get("banned_players"):
                L.append(f"     banned players it flagged: {g['banned_flagged']}/{g['banned_players']} (bans are not all "
                         "for cheating)")
    sc = r.get("setup")
    if sc is not None:
        if "skipped" in sc:
            L.append(f"   setup check: {sc['skipped']}")
        else:
            verdict = ("recordings look like real servers" if sc["auc"] < 0.7 else
                       "recordings differ from real servers" if sc["auc"] < 0.9 else
                       "recordings are easy to tell from real servers: the model may be learning the setup")
            L.append(f"   setup check (recorded legit vs real servers): AUC {sc['auc']:.2f}, {verdict}")
            L.append("     most different: " + ", ".join(
                f"{t['feature']} {t['recorded_median']:g} vs {t['telemetry_median']:g}" for t in sc["top"]))
    return L
