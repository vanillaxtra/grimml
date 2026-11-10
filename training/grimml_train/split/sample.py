from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.config import TrainConfig
from grimml_train.eval.report import window_hours

HOLD_REL = Path("splits") / "live_holdout_accounts.txt"
BANNER = "legit pool is operator-asserted (source=operator_asserted_bulk), not individually verified"


def holdout_path(out_dir: Path) -> Path:
    return out_dir / HOLD_REL


def load_or_write_holdout(accounts: np.ndarray, out_dir: Path, seed: int, regenerate: bool) -> list[str]:
    dest = holdout_path(out_dir)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and not regenerate:
        return [ln.strip() for ln in dest.read_text(encoding="utf-8").splitlines() if ln.strip()]
    rng = np.random.default_rng(seed)
    uniq = np.unique(accounts.astype(str))
    n = max(1, int(round(len(uniq) * 0.2))) if len(uniq) else 0
    chosen = rng.choice(uniq, size=min(n, len(uniq)), replace=False) if n else np.array([], dtype=str)
    dest.write_text("\n".join(sorted(chosen.tolist())) + ("\n" if len(chosen) else ""), encoding="utf-8")
    return chosen.tolist()


def _eligible(windows: pd.DataFrame, holdout: set[str]) -> pd.DataFrame:
    w = windows.copy()
    if "label" in w.columns:
        w = w[w["label"] == "LEGIT_CONFIRMED"]
    if "operator_account" in w.columns:
        w = w[w["operator_account"] != True]
    if "pair_group" in w.columns:
        w = w[w["pair_group"] != "operator"]
    if "anon_player_id" in w.columns and holdout:
        w = w[~w["anon_player_id"].astype(str).isin(holdout)]
    return w


def _cap_stratify(w: pd.DataFrame, cap: int, target: int, seed: int) -> pd.DataFrame:
    if w.empty:
        return w
    rng = np.random.default_rng(seed)
    parts = []
    group_cols = [c for c in ("anon_player_id",) if c in w.columns]
    if not group_cols:
        return w.sample(n=min(target, len(w)), random_state=seed)
    for _, g in w.groupby(group_cols[0], sort=False):
        n = min(cap, len(g))
        if n < len(g):
            g = g.iloc[rng.choice(len(g), size=n, replace=False)]
        parts.append(g)
    out = pd.concat(parts, ignore_index=True) if parts else w.iloc[0:0]
    if "server_tag" in out.columns and "file_date" in out.columns and len(out) > target:
        # keep mix of tag/date by sampling within strata down toward target
        take = []
        strata = list(out.groupby(["server_tag", "file_date"], dropna=False))
        per = max(1, target // max(len(strata), 1))
        for _, g in strata:
            k = min(len(g), per)
            take.append(g.iloc[rng.choice(len(g), size=k, replace=False)])
        out = pd.concat(take, ignore_index=True)
        if len(out) > target:
            out = out.iloc[rng.choice(len(out), size=target, replace=False)]
    elif len(out) > target:
        out = out.iloc[rng.choice(len(out), size=target, replace=False)]
    return out.reset_index(drop=True)


def build_sample(windows: pd.DataFrame, cfg: TrainConfig, regenerate_holdout: bool = False) -> dict:
    if windows.empty:
        raise SystemExit("no windows to sample")
    live_accounts = windows
    if "operator_account" in windows.columns:
        live_accounts = windows[windows["operator_account"] != True]
    hold = load_or_write_holdout(
        live_accounts["anon_player_id"].to_numpy() if "anon_player_id" in live_accounts.columns else np.array([]),
        cfg.out_dir,
        cfg.seed,
        regenerate_holdout,
    )
    hold_set = set(hold)
    elig = _eligible(windows, hold_set)
    all_v = _cap_stratify(elig, cfg.per_account_cap, cfg.sample_target, cfg.seed)
    gml3 = _cap_stratify(
        elig[elig["schema_version"] == 3] if "schema_version" in elig.columns else elig,
        cfg.per_account_cap,
        cfg.sample_target,
        cfg.seed + 1,
    )
    dest = cfg.out_dir / "samples"
    dest.mkdir(parents=True, exist_ok=True)
    cols = [c for c in ("window_key", "window_id", "anon_player_id", "schema_version", "server_tag", "file_date") if c in all_v.columns]
    all_v[cols].to_parquet(dest / "live_legit_v1.parquet", index=False)
    gml3[cols].to_parquet(dest / "live_legit_v1_gml3.parquet", index=False)
    hold_w = windows[windows["anon_player_id"].astype(str).isin(hold_set)] if "anon_player_id" in windows.columns else windows.iloc[0:0]
    hours = float(window_hours(hold_w).sum()) if len(hold_w) else 0.0
    summary = {
        "banner": BANNER,
        "all_versions": int(len(all_v)),
        "gml3_only": int(len(gml3)),
        "holdout_accounts": int(len(hold)),
        "holdout_windows": int(len(hold_w)),
        "holdout_player_hours": round(hours, 2),
        "per_account_cap": cfg.per_account_cap,
        "by_server_tag": all_v["server_tag"].value_counts(dropna=False).head(20).to_dict() if "server_tag" in all_v.columns else {},
        "by_date": all_v["file_date"].value_counts(dropna=False).head(20).to_dict() if "file_date" in all_v.columns else {},
    }
    (dest / "live_legit_v1_summary.json").write_text(
        __import__("json").dumps(summary, indent=2, default=str), encoding="utf-8"
    )
    print(BANNER, flush=True)
    print(summary, flush=True)
    return summary
