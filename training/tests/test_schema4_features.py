"""Schema 4 feature families on hand-built windows with known answers."""
from __future__ import annotations

import math

import numpy as np

from grimml_train.features.schema4 import SCHEMA4_FEATURES, schema4_features

MS = 1_000_000


def frame(tick, yaw, opp=(0, 0, 3000), on_ground=True, client_tick=None, **kw):
    f = {"tick": tick, "yaw_exact": yaw, "pitch_exact": 0.0, "sneak": False, "on_ground": on_ground,
         "client_tick": tick if client_tick is None else client_tick,
         "opp_anon": 7, "opp_dx_milli": opp[0], "opp_dy_milli": opp[1], "opp_dz_milli": opp[2],
         "opp_yaw_centi": 0, "opp_pose": 0, "opp_state": 1, "opp_block_ago_ms": -1, "opp_hurt_ago_ms": 900,
         "nearby_players": 1, "max_gap_ms": 50, "burst": 0}
    f.update(kw)
    return f


def ev(kind, tick, nanos, a=0, b=0, c=0, d=0, g=0, client_tick=None):
    return {"event_type": kind, "tick": tick, "nanos": nanos, "a": a, "b": b, "c": c, "d": d, "e": 0, "f": 0,
            "g": g, "h": 0, "i": 0, "j": 0, "client_tick": tick if client_tick is None else client_tick}


def win(frames, events, schema=4):
    return {"window": {"schema_version": schema}, "frames": frames, "events": events}


def test_old_schema_gives_nan_except_clicks():
    swings = [ev("swing", t, t * 50 * MS) for t in range(0, 40, 2)]
    out = schema4_features(win([], swings, schema=3))
    assert set(out) == set(SCHEMA4_FEATURES)
    assert math.isnan(out["aim_err_yaw_abs_mean"]) and math.isnan(out["jr_reset_rate"])
    assert not math.isnan(out["click_gap_entropy"])  # clicks only need schema 3 swings


def test_aim_error_and_pull():
    # opponent straight ahead (+z, yaw 0); player starts 10 degrees off and closes in steadily (aim assist style)
    yaws = [10 * (0.85 ** i) for i in range(30)]
    out = schema4_features(win([frame(i, y) for i, y in enumerate(yaws)], []))
    assert 0 < out["aim_err_yaw_abs_mean"] < 10
    assert out["aim_pull_share"] == 1.0  # every rotation moved toward the target
    assert out["aim_pull_corr"] > 0.9


def test_offgrid_rotation():
    step = 0.15 * 0.8 ** 3 * 8  # a real sensitivity step
    rng = np.random.default_rng(1)
    on = np.cumsum(rng.integers(1, 6, 60) * step)
    out_on = schema4_features(win([frame(i, float(y)) for i, y in enumerate(on)], []))
    off = np.cumsum(rng.uniform(0.05, 0.6, 60))
    out_off = schema4_features(win([frame(i, float(y)) for i, y in enumerate(off)], []))
    assert out_on["aim_offgrid_frac"] < 0.1
    assert out_off["aim_offgrid_frac"] > out_on["aim_offgrid_frac"]


def test_triggerbot_on_target_and_cooldown():
    frames = [frame(i, 0.0) for i in range(40)]  # crosshair on the opponent the whole time
    attacks = [ev("attack", t, t * 50 * MS, g=1 | (12 << 8)) for t in (5, 18, 31)]
    out = schema4_features(win(frames, attacks))
    assert out["trig_on_target_frac"] == 1.0
    assert out["trig_cooldown_mean"] == 12 and out["trig_cooldown_std"] == 0
    assert out["trig_click_in_hurt_frac"] == 0.0


def test_backtrack_rtt_and_stale_hits():
    frames = [frame(i, 0.0, opp=(0, 0, 2500 if i < 20 else 3800)) for i in range(40)]
    events = []
    for k in range(20):
        events.append(ev("rtt_sent", 2 * k, (2 * k) * 50 * MS, a=k, b=1))
        events.append(ev("rtt", 2 * k + 1, (2 * k) * 50 * MS + 80 * MS, a=k, c=1))
    events += [ev("attack", t, t * 50 * MS, g=1) for t in (21, 23, 25)]  # opponent already out of reach
    out = schema4_features(win(frames, events))
    assert abs(out["bt_rtt_mean"] - 80) < 1e-6 and out["bt_rtt_std"] < 1e-6
    assert out["bt_stale_hit_frac"] == 1.0


def test_jump_reset_perfect_timing():
    frames = [frame(i, 0.0) for i in range(60)]
    events = []
    for k, t in enumerate((5, 20, 35, 50)):
        events.append(ev("knockback", t, t * 50 * MS, a=3000, c=0))
        events.append(ev("rtt_sent", t, t * 50 * MS + 1, a=100 + k, b=1))
        events.append(ev("rtt", t + 2, (t + 2) * 50 * MS, a=100 + k, c=1, client_tick=t + 2))
        events.append(ev("input", t + 2, (t + 2) * 50 * MS + 5, a=1 | 16, client_tick=t + 2))  # jump on arrival
        events.append(ev("input", t + 4, (t + 4) * 50 * MS, a=1, client_tick=t + 4))
    out = schema4_features(win(frames, events))
    assert out["jr_knockbacks"] == 4
    assert out["jr_reset_rate"] == 1.0
    assert out["jr_offset_zero_frac"] == 1.0 and out["jr_offset_std"] == 0


def test_wtap_reaction_and_hold():
    events = [ev("input", 0, 0, a=1)]
    for t in (10, 30, 50, 70):
        events.append(ev("attack", t, t * 50 * MS, g=1))
        events.append(ev("input", t, t * 50 * MS + 20 * MS, a=0))   # release W 20 ms after the hit
        events.append(ev("input", t + 3, t * 50 * MS + 170 * MS, a=1))  # press again after 150 ms
    out = schema4_features(win([frame(i, 0.0) for i in range(80)], events))
    assert out["wtap_rate"] == 1.0
    assert abs(out["wtap_reaction_ms_mean"] - 20) < 1e-6 and out["wtap_reaction_ms_std"] < 1e-6
    assert abs(out["wtap_hold_ms_mean"] - 150) < 1e-6


def test_crystal_break_latency():
    events = []
    for k in range(3):
        events.append(ev("entity_spawn", 10 * k, 10 * k * 50 * MS, a=500 + k, b=1))
        events.append(ev("attack", 10 * k + 1, 10 * k * 50 * MS + 30 * MS, b=500 + k, g=2))
    out = schema4_features(win([frame(i, 0.0) for i in range(40)], events))
    assert out["crystal_breaks"] == 3 and abs(out["crystal_break_ms_mean"] - 30) < 1e-6


def test_shield_axe_reaction():
    frames = [frame(i, 0.0, opp_state=1 | 8, opp_block_ago_ms=40 + i) for i in range(30)]
    events = [ev("hotbar_swap", t, t * 50 * MS, c=1 | (2 << 8)) for t in (5, 15, 25)]
    out = schema4_features(win(frames, events))
    assert out["shield_axe_swaps"] == 3
    assert out["shield_axe_reaction_ms_mean"] == 55
