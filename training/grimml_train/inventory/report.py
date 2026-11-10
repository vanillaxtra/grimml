from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.eval.report import window_hours
from grimml_train.split.sample import BANNER, holdout_path

VALIDITY = [
    "valid_partial",
    "valid_lag",
    "valid_death",
    "valid_respawn",
    "valid_teleport",
    "valid_setback",
    "valid_world",
    "valid_gamemode",
    "valid_vanish",
]


def inventory_report(windows: pd.DataFrame, out_path: Path | None = None) -> dict:
    warnings: list[str] = []
    if windows.empty:
        rep = {"windows": 0, "warnings": ["no windows"]}
    else:
        def vc(col, n=40):
            if col not in windows.columns:
                return {}
            return {str(k): int(v) for k, v in windows[col].value_counts(dropna=False).head(n).items()}

        n = len(windows)
        labeled = windows["label"] != "UNLABELED" if "label" in windows.columns else pd.Series(False, index=windows.index)
        cheat = windows["label"].eq("CHEAT") if "label" in windows.columns else labeled
        legit = windows["label"].eq("LEGIT_CONFIRMED") if "label" in windows.columns else labeled
        bulk = (
            windows["label_source"].eq("operator_asserted_bulk")
            if "label_source" in windows.columns
            else pd.Series(False, index=windows.index)
        )
        gml1 = int((windows["schema_version"] == 1).sum()) if "schema_version" in windows.columns else 0
        if n and gml1 / n > 0.2:
            warnings.append(
                f"GML1 share {gml1}/{n} ({gml1/n:.1%}) > 20%. no positions/targets for aim models."
            )
        acct = windows["anon_player_id"].value_counts() if "anon_player_id" in windows.columns else pd.Series(dtype=int)
        n_acct = int(acct.size)
        if n_acct < 300:
            warnings.append(f"only {n_acct} accounts (want ≥300)")
        if n_acct and n and acct.iloc[0] / n > 0.03:
            warnings.append(f"heaviest account holds {acct.iloc[0]/n:.1%} of windows (>3%)")
        total = acct.sum() if len(acct) else 0
        def top_share(frac):
            if not len(acct) or total <= 0:
                return 0.0
            k = max(1, int(np.ceil(len(acct) * frac)))
            return float(acct.iloc[:k].sum() / total)

        hrs = window_hours(windows)
        ping = pd.to_numeric(windows["ping_ms"], errors="coerce") if "ping_ms" in windows.columns else pd.Series(dtype=float)
        length_s = hrs * 3600
        hold_n = 0
        hold_win = 0
        hold_hrs = 0.0
        if out_path:
            hp = holdout_path(out_path.parent if out_path.suffix else out_path)
            if hp.exists() and "anon_player_id" in windows.columns:
                hold_ids = {ln.strip() for ln in hp.read_text(encoding="utf-8").splitlines() if ln.strip()}
                hold_n = len(hold_ids)
                hw = windows[windows["anon_player_id"].astype(str).isin(hold_ids)]
                hold_win = int(len(hw))
                hold_hrs = float(window_hours(hw).sum()) if len(hw) else 0.0
        validity = {}
        for c in VALIDITY:
            if c in windows.columns:
                validity[c] = float(pd.to_numeric(windows[c], errors="coerce").fillna(0).mean())

        def gb_by(col):
            if "file_bytes" not in windows.columns or col not in windows.columns:
                return {}
            g = windows.groupby(col, dropna=False)["file_bytes"].sum() / 1e9
            return {str(k): round(float(v), 4) for k, v in g.head(40).items()}

        def accts_by(col):
            if col not in windows.columns or "anon_player_id" not in windows.columns:
                return {}
            g = windows.groupby(col, dropna=False)["anon_player_id"].nunique()
            return {str(k): int(v) for k, v in g.head(40).items()}

        if int(bulk.sum()):
            print(BANNER, flush=True)
            print(
                f"hold-out accounts={hold_n} windows={hold_win} player-hours={hold_hrs:.2f}",
                flush=True,
            )
        rep = {
            "windows": int(n),
            "labeled": int(labeled.sum()),
            "unlabeled": int((~labeled).sum()),
            "cheat": int(cheat.sum()),
            "legit": int(legit.sum()),
            "operator_asserted_bulk": int(bulk.sum()),
            "unique_accounts": n_acct,
            "estimated_player_hours": round(float(hrs.sum()), 2),
            "windows_per_account": {
                "mean": float(acct.mean()) if len(acct) else 0.0,
                "p50": float(acct.quantile(0.5)) if len(acct) else 0.0,
                "p90": float(acct.quantile(0.9)) if len(acct) else 0.0,
                "p99": float(acct.quantile(0.99)) if len(acct) else 0.0,
                "max": int(acct.max()) if len(acct) else 0,
                "top1_share": top_share(0.01),
                "top5_share": top_share(0.05),
                "top10_share": top_share(0.10),
                "top20": {str(k): int(v) for k, v in acct.head(20).items()},
            },
            "by_schema": vc("schema_version"),
            "gml1_share": (gml1 / n) if n else 0.0,
            "by_server": vc("server_id"),
            "by_server_tag": vc("server_tag"),
            "by_kit": vc("kit"),
            "by_map": vc("map_id"),
            "by_file_date": vc("file_date"),
            "label_coverage": vc("label"),
            "label_source": vc("label_source"),
            "by_module_id": vc("mark_module_id"),
            "by_client_id": vc("mark_client_id"),
            "test_account_share": float(windows["is_test_account"].mean()) if "is_test_account" in windows else 0.0,
            "bedrock_share": float(pd.to_numeric(windows["is_bedrock"], errors="coerce").fillna(0).mean()) if "is_bedrock" in windows.columns else None,
            "ping": {
                "p50": float(ping.quantile(0.5)) if len(ping) else None,
                "p90": float(ping.quantile(0.9)) if len(ping) else None,
                "p99": float(ping.quantile(0.99)) if len(ping) else None,
            },
            "window_length_s": {
                "p50": float(np.nanpercentile(length_s, 50)) if n else None,
                "p90": float(np.nanpercentile(length_s, 90)) if n else None,
            },
            "validity_rates": validity,
            "holdout_accounts": hold_n,
            "holdout_windows": hold_win,
            "holdout_player_hours": round(hold_hrs, 2),
            "gb_by_label": gb_by("label"),
            "gb_by_source": gb_by("label_source"),
            "gb_by_schema": gb_by("schema_version"),
            "gb_by_server_tag": gb_by("server_tag"),
            "gb_by_date": gb_by("file_date"),
            "accounts_by_label": accts_by("label"),
            "accounts_by_source": accts_by("label_source"),
            "warnings": warnings,
        }
        if int(bulk.sum()):
            rep["provenance"] = BANNER
    if out_path:
        out_path = Path(out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        md = _markdown(rep)
        out_path.write_text(md, encoding="utf-8")
        json_path = out_path.with_suffix(".json")
        json_path.write_text(json.dumps(rep, indent=2, default=str), encoding="utf-8")
    return rep


def _markdown(rep: dict) -> str:
    lines = ["# grimml inventory", ""]
    if rep.get("provenance"):
        lines.append(f"**{rep['provenance']}**")
        lines.append("")
    for w in rep.get("warnings") or []:
        lines.append(f"**WARNING:** {w}")
        lines.append("")
    skip = {"warnings", "windows_per_account", "validity_rates", "ping", "window_length_s", "provenance"}
    for k, v in rep.items():
        if k in skip:
            continue
        lines.append(f"- **{k}:** `{v}`")
    wpa = rep.get("windows_per_account") or {}
    if wpa:
        lines.append("")
        lines.append("## windows per account")
        lines.append(
            f"- mean `{wpa.get('mean')}` p50 `{wpa.get('p50')}` p90 `{wpa.get('p90')}` "
            f"p99 `{wpa.get('p99')}` max `{wpa.get('max')}`"
        )
        lines.append(
            f"- top 1%/5%/10% accounts hold `{wpa.get('top1_share')}` / `{wpa.get('top5_share')}` / `{wpa.get('top10_share')}`"
        )
    if rep.get("validity_rates"):
        lines.append("")
        lines.append("## validity flag rates (report only, not filtered)")
        for k, v in rep["validity_rates"].items():
            lines.append(f"- {k}: `{v:.4f}`")
    return "\n".join(lines) + "\n"
