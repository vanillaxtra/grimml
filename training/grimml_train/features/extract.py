from __future__ import annotations

import numpy as np
import pandas as pd

from grimml_train.features.forbidden import GRIM_FEATURE_PREFIXES, assert_no_forbidden_features

NAN = float("nan")
SNAP_YAW_CENTI = 1500

# combat kinematics only; identity/grim excluded. ping/jitter watched as confounds (C5)
FEATURE_COLUMNS = [
    "yaw_vel_mean",
    "yaw_vel_std",
    "yaw_vel_abs_mean",
    "yaw_jerk_mean",
    "yaw_snap_rate",
    "gcd_grid",
    "pitch_vel_mean",
    "pitch_range",
    "speed_mean",
    "on_ground_frac",
    "attack_count",
    "cps",
    "click_gap_mean_ms",
    "click_gap_cv",
    "ping_ms",
    "jitter_ms",
    "frame_count",
]


def _arr(vals) -> np.ndarray:
    if not vals:
        return np.empty(0, dtype=np.float64)
    return np.asarray(vals, dtype=np.float64)


def _stats(x: np.ndarray) -> tuple[float, float, float, float]:
    if x.size == 0:
        return NAN, NAN, NAN, NAN
    return float(np.mean(x)), float(np.std(x)), float(np.min(x)), float(np.max(x))


def _gcd_grid(deltas: np.ndarray) -> float:
    d = np.abs(deltas)
    d = d[d > 1.0]
    if d.size < 4:
        return NAN
    return float(np.median(d))


def features_from_decoded(decoded: dict) -> dict:
    frames = decoded.get("frames") or []
    events = decoded.get("events") or []
    # labels/markers are not read here
    events = [e for e in events if e.get("event_type") not in ("marker", "marker_kv")]
    schema = decoded.get("window", {}).get("schema_version", 0)
    yaw = _arr([f.get("yaw_centi") for f in frames if f.get("yaw_centi") is not None])
    pitch = _arr([f.get("pitch_centi") for f in frames if f.get("pitch_centi") is not None])
    xs = _arr([f.get("x_milli") for f in frames if f.get("x_milli") is not None])
    zs = _arr([f.get("z_milli") for f in frames if f.get("z_milli") is not None])
    flags = [f.get("flags") for f in frames]
    acks = [f.get("ack_tick") for f in frames if f.get("ack_tick") not in (None, 0)]

    row = {
        "frame_count": len(frames),
        "event_count": len(events),
        "yaw_vel_mean": NAN,
        "yaw_vel_std": NAN,
        "yaw_vel_abs_mean": NAN,
        "yaw_jerk_mean": NAN,
        "yaw_snap_rate": NAN,
        "gcd_grid": NAN,
        "pitch_vel_mean": NAN,
        "pitch_range": NAN,
        "speed_mean": NAN,
        "on_ground_frac": NAN,
        "attack_count": 0,
        "cps": NAN,
        "click_gap_mean_ms": NAN,
        "click_gap_cv": NAN,
        "has_xyz": bool(xs.size and schema >= 2),
        "timing_ack_compensated": bool(acks),
        "gml1_xyz_nan": schema == 1,
    }
    if yaw.size >= 2:
        dy = np.diff(yaw)
        row["yaw_vel_mean"], row["yaw_vel_std"], _, _ = _stats(dy)
        row["yaw_vel_abs_mean"] = float(np.mean(np.abs(dy)))
        row["gcd_grid"] = _gcd_grid(dy)
        row["yaw_snap_rate"] = float(np.mean(np.abs(dy) > SNAP_YAW_CENTI))
        if dy.size >= 2:
            row["yaw_jerk_mean"] = float(np.mean(np.abs(np.diff(dy))))
    if pitch.size >= 2:
        dp = np.diff(pitch)
        row["pitch_vel_mean"] = float(np.mean(dp))
        row["pitch_range"] = float(np.max(pitch) - np.min(pitch))
    if xs.size >= 2 and zs.size == xs.size:
        dx = np.diff(xs)
        dz = np.diff(zs)
        speed = np.sqrt(dx * dx + dz * dz)
        row["speed_mean"] = float(np.mean(speed))
    grounded = [1.0 for f in flags if f is not None and (int(f) & 1)]
    known = [1 for f in flags if f is not None]
    if known:
        row["on_ground_frac"] = float(len(grounded) / len(known))
    elif schema < 2:
        row["on_ground_frac"] = NAN

    attacks = [e for e in events if e.get("event_type") == "attack"]
    row["attack_count"] = len(attacks)
    nanos = _arr([e.get("nanos") for e in attacks if e.get("nanos") is not None])
    if nanos.size >= 2:
        gaps = np.diff(np.sort(nanos)) / 1e6
        gaps = gaps[gaps > 0]
        if gaps.size:
            mu = float(np.mean(gaps))
            row["click_gap_mean_ms"] = mu
            sd = float(np.std(gaps))
            row["click_gap_cv"] = sd / mu if mu > 0 else NAN
            span = float((nanos.max() - nanos.min()) / 1e9)
            if span > 0:
                row["cps"] = float((nanos.size - 1) / span)
    return row


def extract_window_features(
    windows: pd.DataFrame,
    frames: pd.DataFrame,
    events: pd.DataFrame,
    exclude_grim_features: bool = True,
    weak_label_mask: pd.Series | None = None,
) -> pd.DataFrame:
    if windows.empty:
        return pd.DataFrame()
    grouped_f = {k: g.to_dict("records") for k, g in frames.groupby("window_key")} if not frames.empty else {}
    grouped_e = {k: g.to_dict("records") for k, g in events.groupby("window_key")} if not events.empty else {}
    rows = []
    for _, w in windows.iterrows():
        wk = w["window_key"]
        feat = features_from_decoded(
            {
                "window": {"schema_version": w.get("schema_version", 3)},
                "frames": grouped_f.get(wk, []),
                "events": grouped_e.get(wk, []),
            }
        )
        feat["window_key"] = wk
        feat["ping_ms"] = w.get("ping_ms")
        feat["jitter_ms"] = w.get("jitter_ms")
        rows.append(feat)
    feat = pd.DataFrame(rows)
    assert_no_forbidden_features([c for c in feat.columns if c != "window_key"], allow_grim=not exclude_grim_features)
    return feat
