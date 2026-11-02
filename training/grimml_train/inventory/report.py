from __future__ import annotations

from pathlib import Path

import pandas as pd


def inventory_report(windows: pd.DataFrame, out_path: Path | None = None) -> dict:
    if windows.empty:
        rep = {"windows": 0}
    else:
        def vc(col):
            if col not in windows.columns:
                return {}
            return windows[col].value_counts(dropna=False).head(40).to_dict()

        labeled = windows["label"] != "UNLABELED" if "label" in windows.columns else pd.Series(False, index=windows.index)
        cheat = windows["label"].eq("CHEAT") if "label" in windows.columns else labeled
        legit = windows["label"].eq("LEGIT_CONFIRMED") if "label" in windows.columns else labeled
        rep = {
            "windows": int(len(windows)),
            "labeled": int(labeled.sum()),
            "unlabeled": int((~labeled).sum()),
            "cheat": int(cheat.sum()),
            "legit": int(legit.sum()),
            "by_schema": vc("schema_version"),
            "by_server": vc("server_id"),
            "by_kit": vc("kit"),
            "label_coverage": vc("label"),
            "label_source": vc("label_source"),
            "by_module_id": vc("mark_module_id"),
            "by_client_id": vc("mark_client_id"),
            "test_account_share": float(windows["is_test_account"].mean()) if "is_test_account" in windows else 0.0,
            "valid_partial_share": float(windows["valid_partial"].mean()) if "valid_partial" in windows else None,
            "valid_lag_share": float(windows["valid_lag"].mean()) if "valid_lag" in windows else None,
        }
    if out_path:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        lines = [f"{k}: {v}" for k, v in rep.items()]
        out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return rep
