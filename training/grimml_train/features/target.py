"""Cross-window aim and reach features: attacker eye + look vs the target hitbox at the attack tick.

Needs both the attacker window and a window of the target covering that tick (the fight assembler
writes both), joined on boot id + anon id + server tick. Relative-position windows are skipped.
These are offline research features: the plugin cannot compute them, so they never go into the
exported alert model (see FEATURE_COLUMNS in extract.py).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from grimml_train.decode.records import F_SNEAK
from grimml_train.maps.gmm import line_of_sight, map_key

TARGET_FEATURE_COLUMNS = [
    "aim_err_mean_deg",
    "aim_err_p90_deg",
    "reach_mean",
    "reach_max",
    "ray_miss_rate",
    "los_blocked_rate",
]

HALF_WIDTH = 0.3
HEIGHT = 1.8
SNEAK_HEIGHT = 1.5
EYE = 1.62
SNEAK_EYE = 1.27
MAX_RAY = 6.0


def _relative(frames: list[dict]) -> bool:
    if not frames:
        return True
    f = frames[0]
    return f.get("x_milli") in (None, 0) and f.get("y_milli") in (None, 0) and f.get("z_milli") in (None, 0)


def geometry_rows(decoded: dict) -> tuple[list[dict], list[dict]]:
    win = decoded.get("window") or {}
    frames = decoded.get("frames") or []
    if win.get("schema_version", 0) < 2 or _relative(frames):
        return [], []
    boot = win.get("boot_id")
    anon = win.get("anon_player_id")
    tracks = [
        {
            "boot_id": boot,
            "anon_player_id": anon,
            "tick": f["tick"],
            "x": f["x_milli"] / 1000.0,
            "y": f["y_milli"] / 1000.0,
            "z": f["z_milli"] / 1000.0,
            "yaw": f["yaw_centi"] / 100.0,
            "pitch": f["pitch_centi"] / 100.0,
            "sneak": bool((f.get("flags") or 0) & F_SNEAK),
        }
        for f in frames
    ]
    attacks = [
        {
            "window_key": win.get("window_key"),
            "boot_id": boot,
            "anon_player_id": anon,
            "target_anon": int(e["a"]) & 0xFFFFFFFFFFFFFFFF,
            "tick": e["tick"],
            "map_key": map_key(win.get("map_id"), win.get("world")),
        }
        for e in decoded.get("events") or []
        if e.get("event_type") == "attack" and e.get("a")
    ]
    return tracks, attacks


def look_vector(yaw_deg: float, pitch_deg: float) -> np.ndarray:
    y = math.radians(yaw_deg)
    p = math.radians(pitch_deg)
    return np.array([-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)])


def ray_box(origin: np.ndarray, d: np.ndarray, lo: np.ndarray, hi: np.ndarray) -> float | None:
    tmin, tmax = 0.0, MAX_RAY
    for i in range(3):
        if abs(d[i]) < 1e-12:
            if origin[i] < lo[i] or origin[i] > hi[i]:
                return None
            continue
        t1 = (lo[i] - origin[i]) / d[i]
        t2 = (hi[i] - origin[i]) / d[i]
        t1, t2 = min(t1, t2), max(t1, t2)
        tmin, tmax = max(tmin, t1), min(tmax, t2)
        if tmin > tmax:
            return None
    return tmin


def attack_geometry(att: dict, tgt: dict) -> dict:
    eye = np.array([att["x"], att["y"] + (SNEAK_EYE if att["sneak"] else EYE), att["z"]])
    h = SNEAK_HEIGHT if tgt["sneak"] else HEIGHT
    lo = np.array([tgt["x"] - HALF_WIDTH, tgt["y"], tgt["z"] - HALF_WIDTH])
    hi = np.array([tgt["x"] + HALF_WIDTH, tgt["y"] + h, tgt["z"] + HALF_WIDTH])
    closest = np.clip(eye, lo, hi)
    center = (lo + hi) / 2
    look = look_vector(att["yaw"], att["pitch"])
    to_center = center - eye
    norm = float(np.linalg.norm(to_center))
    cos = float(np.dot(look, to_center) / norm) if norm > 0 else 1.0
    return {
        "aim_err_deg": math.degrees(math.acos(max(-1.0, min(1.0, cos)))),
        "reach": float(np.linalg.norm(closest - eye)),
        "ray_miss": ray_box(eye, look, lo, hi) is None,
        "eye": eye,
        "center": center,
    }


def compute_targets(tracks: pd.DataFrame, attacks: pd.DataFrame, maps: dict | None = None) -> pd.DataFrame:
    cols = ["window_key", *TARGET_FEATURE_COLUMNS]
    if tracks is None or attacks is None or tracks.empty or attacks.empty:
        return pd.DataFrame(columns=cols)
    tr = tracks.drop_duplicates(["boot_id", "anon_player_id", "tick"]).set_index(["boot_id", "anon_player_id", "tick"])
    lookup = tr.to_dict("index")
    rows = []
    for a in attacks.itertuples(index=False):
        me = lookup.get((a.boot_id, a.anon_player_id, a.tick))
        if me is None:
            continue
        them = None
        for dt in (0, -1, 1):
            them = lookup.get((a.boot_id, a.target_anon, a.tick + dt))
            if them is not None:
                break
        if them is None:
            continue
        g = attack_geometry(me, them)
        los = None
        snap = (maps or {}).get(getattr(a, "map_key", None))
        if snap is not None:
            los = line_of_sight(snap, g["eye"], g["center"])
        rows.append({"window_key": a.window_key, "aim_err_deg": g["aim_err_deg"], "reach": g["reach"],
                     "ray_miss": g["ray_miss"], "los_blocked": None if los is None else (not los)})
    if not rows:
        return pd.DataFrame(columns=cols)
    df = pd.DataFrame(rows)
    out = []
    for wk, g in df.groupby("window_key"):
        blocked = g["los_blocked"].dropna()
        out.append({
            "window_key": wk,
            "aim_err_mean_deg": float(g["aim_err_deg"].mean()),
            "aim_err_p90_deg": float(np.quantile(g["aim_err_deg"], 0.9)),
            "reach_mean": float(g["reach"].mean()),
            "reach_max": float(g["reach"].max()),
            "ray_miss_rate": float(g["ray_miss"].mean()),
            "los_blocked_rate": float(blocked.astype(float).mean()) if len(blocked) else float("nan"),
        })
    return pd.DataFrame(out, columns=cols)
