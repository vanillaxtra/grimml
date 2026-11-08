from __future__ import annotations

from pathlib import Path

import pandas as pd
import yaml

# module name -> cheat family. aim = where the crosshair goes, macro = when keys and items happen
AIM_MODULES = {
    "aim_assist", "killaura", "silent_aim", "triggerbot", "reach", "auto_wind_charge_hitter",
}
MACRO_MODULES = {
    "autoclicker", "auto_totem", "jump_reset", "auto_w_tap", "stun_slam", "auto_breach_swap", "auto_lunge",
    "attribute_swap", "auto_shield", "auto_shield_break", "anchor_macro", "auto_crystal", "auto_mace",
    "auto_xbow_cart", "auto_pearl_catch", "auto_diagonl_pearl_catch", "scaffold",
}
FAMILIES = ("legit", "aim", "macro", "other")

AIM_COLUMNS = [
    "yaw_vel_mean", "yaw_vel_std", "yaw_vel_abs_mean", "yaw_jerk_mean", "yaw_snap_rate", "gcd_grid",
    "pitch_vel_mean", "pitch_range", "attack_count", "ping_ms", "jitter_ms", "frame_count",
    "aim_err_mean_deg", "aim_err_p90_deg", "reach_mean", "reach_max", "ray_miss_rate", "los_blocked_rate",
]
MACRO_COLUMNS = [
    "attack_count", "cps", "click_gap_mean_ms", "click_gap_cv", "on_ground_frac", "speed_mean",
    "retotem_ms_min", "jump_reset_rate", "hotbar_swap_count", "swap_attack_gap_ms_min",
    "ping_ms", "jitter_ms", "frame_count",
]


def module_names(taxonomy_path: Path | None) -> dict[int, str]:
    """1-based yaml order, same ids the plugin writes into MARKER records."""
    if taxonomy_path is None or not Path(taxonomy_path).exists():
        return {}
    tax = yaml.safe_load(Path(taxonomy_path).read_text(encoding="utf-8")) or {}
    return {i + 1: str(name) for i, name in enumerate((tax.get("modules") or {}).keys())}


def family_of(module_name: str | None) -> str:
    if module_name in AIM_MODULES:
        return "aim"
    if module_name in MACRO_MODULES:
        return "macro"
    return "other"


def add_family(df: pd.DataFrame, names: dict[int, str]) -> pd.DataFrame:
    out = df.copy()
    mod = pd.to_numeric(out.get("mark_module_id"), errors="coerce")
    fam = mod.map(lambda m: family_of(names.get(int(m))) if pd.notna(m) else "other")
    out["family"] = fam.where(out["label"] == "CHEAT", "legit")
    out.loc[~out["label"].isin(["CHEAT", "LEGIT_CONFIRMED"]), "family"] = None
    return out
