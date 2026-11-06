from __future__ import annotations

from pathlib import Path

import pandas as pd


def load_cheat_log(path: Path) -> pd.DataFrame:
    if path is None or not Path(path).exists():
        return pd.DataFrame()
    return pd.read_csv(path)


def apply_cheat_log(windows: pd.DataFrame, log: pd.DataFrame) -> pd.DataFrame:
    out = windows.copy()
    if log.empty or "mark_id" not in out.columns:
        return out
    id_col = "marker_id" if "marker_id" in log.columns else ("id" if "id" in log.columns else None)
    if id_col is None:
        return out
    log = log.copy()
    log["_mid"] = log[id_col].astype(str).str.lower()
    out["_mid"] = out["mark_id"].where(out["mark_id"].notna(), "").astype(str).str.lower()
    keep = [c for c in ("client", "module", "operator", "settings", "notes", "defender") if c in log.columns]
    extra = log.drop_duplicates("_mid")[["_mid", *keep]]
    merged = out.merge(extra, on="_mid", how="left", suffixes=("", "_log"))
    if "operator" in merged.columns and "operator_log" in merged.columns:
        merged["operator"] = merged["operator"].fillna(merged["operator_log"])
        merged.drop(columns=["operator_log"], inplace=True)
    merged.drop(columns=["_mid"], inplace=True)
    return merged
