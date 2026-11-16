"""Spear-mace features (spearmace.py) on hand-built windows with known answers."""
from __future__ import annotations

import math

from grimml_train.features.schema4 import SCHEMA4_FEATURES, schema4_features
from grimml_train.features.spearmace import (
    AXE, MACE, SPEAR, SPEAR_IDS, SPEAR_MACE_FEATURES, item_id, resolve_cat, spear_mace_features,
)

MS = 1_000_000
SWORD, OTHER = 1, 0
NETHERITE_SPEAR = item_id("netherite_spear")


def fr(tick, y=0, ground=True, yaw=0.0, opp_state=None):
    f = {"tick": tick, "y_milli": y, "on_ground": ground, "yaw_exact": yaw, "pitch_exact": 0.0, "client_tick": tick}
    if opp_state is not None:
        f.update({"opp_anon": 7, "opp_dx_milli": 0, "opp_dy_milli": 0, "opp_dz_milli": 3000, "opp_state": opp_state,
                  "opp_block_ago_ms": 300, "opp_hurt_ago_ms": -1, "sneak": False})
    return f


def ev(kind, ms, a=0, b=0, c=0, d=0, e=0, g=0, ct=None):
    tick = int(ms // 50)
    return {"event_type": kind, "tick": tick, "nanos": int(ms * MS), "a": a, "b": b, "c": c, "d": d, "e": e, "f": 0,
            "g": g, "client_tick": tick if ct is None else ct}


def swap(ms, frm, to, ct=None, from_id=0, to_id=0):
    return ev("hotbar_swap", ms, a=1, b=2, c=frm | (to << 8), d=from_id, e=to_id, ct=ct)


def hit(ms, cat, target=5, ct=None):
    return ev("attack", ms, a=99, b=target, c=cat << 8, g=1, ct=ct)


def stab(ms, ct=None):
    return ev("break", ms, a=8, ct=ct)


def run(events, frames=None, schema=4):
    frames = frames if frames is not None else [fr(t) for t in range(200)]
    return schema4_features({"window": {"schema_version": schema}, "frames": frames, "events": events})


def test_names_appended_at_the_end():
    assert SCHEMA4_FEATURES[-len(SPEAR_MACE_FEATURES):] == SPEAR_MACE_FEATURES
    assert SCHEMA4_FEATURES[47] == "grid_toward_minus_away"  # existing indices did not move


def test_spear_resolved_from_item_id():
    assert len(SPEAR_IDS) == 7 and NETHERITE_SPEAR in SPEAR_IDS
    assert resolve_cat(0, NETHERITE_SPEAR) == SPEAR
    assert resolve_cat(MACE, item_id("mace")) == MACE
    # an old recording: spear written as category 0, recognised through the ids on the swap
    out = spear_mace_features([], [swap(0, SWORD, OTHER, to_id=NETHERITE_SPEAR), stab(1),
                                   swap(60, OTHER, SWORD, from_id=NETHERITE_SPEAR)])
    assert out["lunge_stabs"] == 1


def _lunges(start_ms, every_ms, back_ms, n, ticks_back=1):
    out = []
    for i in range(n):
        t = start_ms + i * every_ms
        out += [swap(t, SWORD, SPEAR), stab(t + 0.3), swap(t + back_ms, SPEAR, SWORD, ct=int(t // 50) + ticks_back)]
    return out


def test_macro_lunge_is_fast_and_regular():
    out = run(_lunges(0, 200, 50, 12))
    assert out["lunge_stabs"] == 12
    assert out["lunge_swap_in_frac"] == 1.0
    assert math.isclose(out["lunge_back_ms_mean"], 49.7, abs_tol=1e-6)  # stab at +0.3 ms, swap back at +50
    assert out["lunge_back_ms_std"] < 1e-6
    assert out["lunge_back_ticks_mode_frac"] == 1.0
    assert math.isclose(out["lunge_interval_ms_mean"], 200.0, abs_tol=1e-6)
    assert out["lunge_interval_cv"] < 1e-6


def test_hand_lunge_is_slower_and_irregular():
    events = []
    t = 0.0
    for i, (gap, back) in enumerate([(520, 140), (700, 210), (450, 110), (900, 260), (610, 170)]):
        events += [swap(t, SWORD, SPEAR), stab(t), swap(t + back, SPEAR, SWORD, ct=int((t + back) // 50))]
        t += gap
    out = run(events)
    assert out["lunge_back_ms_mean"] > 150 and out["lunge_back_ms_std"] > 40
    assert out["lunge_interval_cv"] > 0.2
    assert out["lunge_back_ticks_mode_frac"] < 1.0


def test_stab_while_holding_the_spear_is_not_a_swap_in():
    out = run([swap(0, SWORD, SPEAR), stab(400), stab(1100), stab(1800)])
    assert out["lunge_swap_in_frac"] == 0.0
    assert math.isnan(out["lunge_swap_stab_ms_mean"])


def test_swap_after_a_hit_in_the_same_client_tick_is_an_order_violation():
    ok = run([swap(0, SWORD, MACE, ct=10), hit(1, MACE, ct=10), swap(60, MACE, SWORD, ct=11)])
    bad = run([swap(0, SWORD, MACE, ct=10), hit(1, MACE, ct=10), swap(2, MACE, SWORD, ct=10)])
    assert ok["swap_order_violations"] == 0
    assert bad["swap_order_violations"] == 1
    # no client ticks (schema 3 or a pre-1.21.2 client): unknown, not zero
    assert math.isnan(run([swap(0, SWORD, MACE, ct=0), hit(1, MACE, ct=0)], schema=3)["swap_order_violations"])


def test_mace_fall_from_the_apex_not_from_ground():
    # launched from y 0 to a 9-block apex at tick 20, falling back; hits at ticks 30 and 34 (34 = last tick in the air)
    ys = [0] * 10 + [int(9000 * (1 - ((t - 20) / 10) ** 2)) for t in range(10, 35)] + [0] * 5
    ys[34] = 2000
    frames = [fr(t, y=ys[t], ground=(ys[t] == 0 and (t < 10 or t >= 35))) for t in range(40)]
    out = run([swap(1490, SWORD, MACE), hit(1500, MACE), swap(1550, MACE, SWORD),
               swap(1690, SWORD, MACE), hit(1700, MACE), swap(1750, MACE, SWORD)], frames)
    assert out["mace_hits"] == 2
    assert math.isclose(out["mace_fall_mean"], ((9000 - ys[30]) + (9000 - 2000)) / 2 / 1000, abs_tol=1e-9)
    assert out["mace_apex_ticks_mean"] == 12.0
    assert out["mace_land_tick_frac"] == 0.5
    assert out["mace_swap_in_frac"] == 1.0
    assert math.isclose(out["mace_swap_hit_ms_mean"], 10.0, abs_tol=1e-6)
    assert math.isclose(out["mace_back_ms_mean"], 50.0, abs_tol=1e-6)


def test_silent_aim_snap_and_back_on_the_mace_hit():
    frames = [fr(t, yaw=0.0) for t in range(40)]
    for k in (10, 20, 30):
        frames[k]["yaw_exact"] = 35.0  # snaps 35 degrees for the hit tick, back the tick after
    out = run([hit(k * 50, MACE) for k in (10, 20, 30)], frames)
    assert out["mace_snap_deg_mean"] == 35.0
    assert out["mace_snapback_frac"] == 1.0


def test_stun_slam_axe_then_mace_one_tick_later():
    events, frames = [], [fr(t, opp_state=8) for t in range(200)]
    for i in range(4):
        t = i * 1000
        events += [swap(t, SWORD, AXE, ct=t // 50), hit(t + 1, AXE, ct=t // 50),
                   swap(t + 33, AXE, MACE, ct=t // 50 + 1), hit(t + 34, MACE, ct=t // 50 + 1),
                   swap(t + 80, MACE, SWORD, ct=t // 50 + 2)]
    out = run(events, frames)
    assert out["stun_slams"] == 4
    assert math.isclose(out["stun_gap_ms_mean"], 33.0, abs_tol=1e-6)
    assert out["stun_gap_ms_std"] < 1e-6
    assert out["stun_gap_ticks_mode_frac"] == 1.0
    assert math.isclose(out["stun_axe_swap_hit_ms_mean"], 1.0, abs_tol=1e-6)
    assert math.isclose(out["stun_axe_to_mace_swap_ms_mean"], 32.0, abs_tol=1e-6)
    assert out["stun_block_frac"] == 1.0


def test_stun_needs_the_same_target_and_a_short_gap():
    out = run([hit(0, AXE, target=5), hit(30, MACE, target=6), hit(1000, AXE), hit(1700, MACE)])
    assert out["stun_slams"] == 0


def test_schema3_gets_timing_but_no_aim_or_ticks():
    out = run(_lunges(0, 200, 50, 6) + [hit(5000, MACE), hit(6000, MACE)], schema=3)
    assert out["lunge_stabs"] == 6 and not math.isnan(out["lunge_back_ms_mean"])
    assert math.isnan(out["mace_snap_deg_mean"]) and math.isnan(out["aim_err_yaw_abs_mean"])
    assert math.isnan(run([], schema=2)["lunge_stabs"])


# ---------------------------------------------------------------- live LungeRate check

def _chain(gap_ms, n, start=1000.0):
    """n macro swap-lunges gap_ms apart: swap to the spear, STAB 10 ms later, swap back 50 ms after the STAB."""
    acts = []
    for i in range(n):
        t = start + i * gap_ms
        acts.append({"k": 1, "n": int(t * MS), "to": SPEAR})
        acts.append({"k": 3, "n": int((t + 10) * MS)})
        acts.append({"k": 1, "n": int((t + 60) * MS), "to": SWORD})
    return acts


def _replay(acts, **kw):
    from grimml_train.features.spearmace import LiveLunge
    g = LiveLunge(**kw)
    return [a["n"] for a in acts if g.feed(a)], g


def test_live_lunge_macro_alerts_after_count_fast_gaps():
    alerts, g = _replay(_chain(200, 7))
    assert g.lunges == 7
    assert alerts == [int((1000 + 6 * 200 + 10) * MS)]  # 6 fast gaps -> alert on the 7th lunge


def test_live_lunge_hand_speed_never_alerts():
    alerts, _ = _replay(_chain(350, 200))
    assert alerts == []


def test_live_lunge_bunched_and_slow_gaps_do_not_count():
    assert _replay(_chain(50, 20))[0] == []          # lag bunching (< 75 ms)
    assert _replay(_chain(200, 6))[0] == []          # only 5 fast gaps


def test_live_lunge_needs_a_fresh_swap_to_the_spear():
    acts = []
    for i in range(20):  # holding the spear, jabbing, no swaps
        acts.append({"k": 3, "n": int((1000 + i * 200) * MS)})
    assert _replay(acts)[0] == []
    late = [a if a["k"] != 3 else {"k": 3, "n": a["n"] + 200 * MS} for a in _chain(400, 10)]  # STAB 210 ms after swap
    assert _replay(late)[1].lunges == 0


def test_live_lunge_window_expires_old_fast_gaps():
    acts = _chain(200, 4) + _chain(200, 4, start=40_000.0)  # 3 + 3 fast gaps, 39 s apart
    assert _replay(acts)[0] == []
