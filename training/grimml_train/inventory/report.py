from __future__ import annotations

import json
from pathlib import Path

import pandas as pd


def inventory_report(windows: pd.DataFrame, out_path: Path | None = None) -> dict:
    if windows.empty:
        rep = {"windows": 0, "warnings": ["no windows"]}
    else:
        def vc(col, n=40):
            if col not in windows.columns:
                return {}
            return {str(k): int(v) for k, v in windows[col].value_counts(dropna=False).head(n).items()}

        labeled = windows["label"] != "UNLABELED" if "label" in windows.columns else pd.Series(False, index=windows.index)
        cheat = windows["label"].eq("CHEAT") if "label" in windows.columns else labeled
        legit = windows["label"].eq("LEGIT_CONFIRMED") if "label" in windows.columns else labeled
        by_schema = vc("schema_version")
        n = len(windows)
        gml1 = int((windows["schema_version"] == 1).sum()) if "schema_version" in windows.columns else 0
        warnings = []
        if n and gml1 / n > 0.5:
            warnings.append(
                f"GML1 dominates ({gml1}/{n} windows). no positions, no targets-as-anon, no in-file MARKER labels."
            )
        acct = windows["anon_player_id"].value_counts() if "anon_player_id" in windows.columns else pd.Series(dtype=int)
        rep = {
            "windows": int(n),
            "labeled": int(labeled.sum()),
            "unlabeled": int((~labeled).sum()),
            "cheat": int(cheat.sum()),
            "legit": int(legit.sum()),
            "unique_accounts": int(acct.size),
            "windows_per_account": {
                "mean": float(acct.mean()) if len(acct) else 0.0,
                "p50": float(acct.median()) if len(acct) else 0.0,
                "max": int(acct.max()) if len(acct) else 0,
                "top20": {str(k): int(v) for k, v in acct.head(20).items()},
            },
            "by_schema": by_schema,
            "by_server": vc("server_id"),
            "by_kit": vc("kit"),
            "by_map": vc("map_id"),
            "by_file_date": vc("file_date"),
            "label_coverage": vc("label"),
            "label_source": vc("label_source"),
            "by_module_id": vc("mark_module_id"),
            "by_client_id": vc("mark_client_id"),
            "test_account_share": float(windows["is_test_account"].mean()) if "is_test_account" in windows else 0.0,
            "bedrock_share": float(windows["is_bedrock"].mean()) if "is_bedrock" in windows.columns else None,
            "valid_partial_share": float(windows["valid_partial"].mean()) if "valid_partial" in windows else None,
            "valid_lag_share": float(windows["valid_lag"].mean()) if "valid_lag" in windows else None,
            "valid_teleport_share": float(windows["valid_teleport"].mean()) if "valid_teleport" in windows else None,
            "warnings": warnings,
        }
    if out_path:
        out_path = Path(out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        md = _markdown(rep)
        out_path.write_text(md, encoding="utf-8")
        json_path = out_path.with_suffix(".json") if out_path.suffix != ".json" else out_path
        if out_path.suffix in (".md", ".txt"):
            json_path = out_path.with_suffix(".json")
        json_path.write_text(json.dumps(rep, indent=2, default=str), encoding="utf-8")
    return rep


def _markdown(rep: dict) -> str:
    lines = ["# grimml inventory", ""]
    for w in rep.get("warnings") or []:
        lines.append(f"**WARNING:** {w}")
        lines.append("")
    skip = {"warnings", "windows_per_account"}
    for k, v in rep.items():
        if k in skip:
            continue
        lines.append(f"- **{k}:** `{v}`")
    wpa = rep.get("windows_per_account") or {}
    if wpa:
        lines.append("")
        lines.append("## windows per account")
        lines.append(f"- mean `{wpa.get('mean')}` p50 `{wpa.get('p50')}` max `{wpa.get('max')}`")
    return "\n".join(lines) + "\n"
