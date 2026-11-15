"""Features that need schema 4 data (docs/schema4.md). Every value is NaN on schema 1-3 windows (except click_* and the
spear-mace timing family, which schema 3 already carries) and whenever a window has too little of the relevant data, so these columns can sit next to the 13 core features without changing them.

Families:
  aim_*        aim assist / silent aim: aim error to the opponent, pull toward them, tracking lag, off-grid rotation
  trig_*       triggerbot: where the crosshair was when the player clicked, cooldown and hurt time at the click
  bt_*         backtrack / fake lag: transaction round trips, ping in reach vs out of reach, stale hits, packet gaps
  jr_*         auto jump reset: jump timing against the client tick the knockback arrived on
  wtap_*       auto W-tap: forward/sprint release after own hits
  click_*      autoclicker: swing timing distribution (works on schema 3 too, but listed here with the new set)
  crystal_* / anchor_* / shield_*   crystal and anchor macros, auto shield breaker
  swap_* / lunge_* / mace_* / stun_* Instant Lunge, Auto Mace, Auto Stun Slam (spearmace.py)

Geometry: Minecraft yaw 0 looks toward +z, yaw 90 toward -x; pitch is positive looking down. Eye height 1.62
(1.27 sneaking), aim point = opponent chest (0.9 of a 1.8 hitbox), hitbox half-width 0.3.
"""
from __future__ import annotations

import math

from grimml_train.features.aimgrid import grid_features
from grimml_train.features.spearmace import SPEAR_MACE_FEATURES, mace_aim_features, spear_mace_features

import numpy as np

from grimml_train.decode import records as R

NAN = float("nan")
TICK_MS = 50.0
EYE = 1.62
EYE_SNEAK = 1.27
HALF_WIDTH = 0.3
AXE = 2

SCHEMA4_FEATURES = [
    "aim_err_yaw_abs_mean", "aim_err_yaw_abs_median", "aim_err_pitch_abs_mean", "aim_pull_share", "aim_pull_corr",
    "aim_track_lag_ticks", "aim_rot_step", "aim_offgrid_frac",
    "trig_on_target_frac", "trig_entry_to_click_ticks_mean", "trig_entry_to_click_ticks_std",
    "trig_cooldown_mean", "trig_cooldown_std", "trig_click_in_hurt_frac",
    "bt_rtt_mean", "bt_rtt_std", "bt_pong_burst_frac", "bt_rtt_near_minus_far", "bt_stale_hit_frac",
    "bt_implied_lag_ticks_mean", "bt_gap_p95_ms", "bt_burst_mean",
    "jr_knockbacks", "jr_reset_rate", "jr_offset_mean", "jr_offset_std", "jr_offset_zero_frac",
    "wtap_rate", "wtap_reaction_ms_mean", "wtap_reaction_ms_std", "wtap_hold_ms_mean", "wtap_hold_ms_std",
    "wtap_same_tick_frac",
    "click_gap_kurtosis", "click_gap_entropy", "click_double_frac",
    "crystal_breaks", "crystal_break_ms_mean", "crystal_break_ms_std", "anchor_clicks", "anchor_gap_ms_std",
    "shield_axe_swaps", "shield_axe_reaction_ms_mean", "shield_axe_reaction_ms_std",
    # mouse grid against a step learned with the sensitivity formula (aimgrid.py), appended last
    "grid_off_frac", "grid_off_toward", "grid_off_away", "grid_toward_minus_away",
    # spear-mace (spearmace.py): swap_* / lunge_* / mace_* / stun_*, appended last
    *SPEAR_MACE_FEATURES,
]


def _wrap(deg):
    return (np.asarray(deg) + 180.0) % 360.0 - 180.0


def _mean(x):
    return float(np.mean(x)) if len(x) else NAN


def _std(x):
    return float(np.std(x)) if len(x) >= 2 else NAN


def _target_angles(f: dict) -> tuple[float, float, float]:
    """(yaw, pitch, distance) from the player's eye to the opponent's chest for one frame."""
    dx = f["opp_dx_milli"] / 1000.0
    dz = f["opp_dz_milli"] / 1000.0
    eye = EYE_SNEAK if f.get("sneak") else EYE
    dy = f["opp_dy_milli"] / 1000.0 + 0.9 - eye
    horiz = math.hypot(dx, dz)
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, horiz))
    return yaw, pitch, math.sqrt(horiz * horiz + dy * dy)


def _frame_index_at(frames: list[dict], tick: int) -> int:
    """Last frame at or before a server tick (frames are in order)."""
    lo, hi = 0, len(frames) - 1
    best = -1
    while lo <= hi:
        mid = (lo + hi) // 2
        if frames[mid]["tick"] <= tick:
            best = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return best


def schema4_features(decoded: dict) -> dict:
    row = {k: NAN for k in SCHEMA4_FEATURES}
    frames = decoded.get("frames") or []
    events = [e for e in (decoded.get("events") or []) if e.get("event_type") not in ("marker", "marker_kv")]
    _click(row, events)
    schema = decoded.get("window", {}).get("schema_version") or 0
    if schema >= 3:
        row.update(spear_mace_features(frames, events))
    if schema < 4:
        return row
    aim = _aim_track(frames)
    _aim(row, frames, aim)
    row.update(grid_features(frames, aim))
    _trigger(row, frames, events, aim)
    _backtrack(row, frames, events, aim)
    _jump_reset(row, frames, events)
    _wtap(row, events)
    _crystal_anchor(row, events)
    _shield(row, frames, events)
    row.update(mace_aim_features(frames, events, aim))
    return row


# ---------------------------------------------------------------- aim

def _aim_track(frames: list[dict]) -> list[dict | None]:
    """Per frame: aim error to the opponent (yaw/pitch, degrees) and the opponent's angular half-size, or None."""
    out: list[dict | None] = []
    for f in frames:
        if f.get("opp_anon") is None or f.get("yaw_exact") is None:
            out.append(None)
            continue
        ty, tp, dist = _target_angles(f)
        out.append({
            "err_yaw": float(_wrap(f["yaw_exact"] - ty)),
            "err_pitch": float(f["pitch_exact"] - tp),
            "target_yaw": ty,
            "dist": dist,
            "half_angle": math.degrees(math.atan2(HALF_WIDTH, max(dist, 0.1))),
        })
    return out


def _aim(row: dict, frames: list[dict], aim: list) -> None:
    ey = [a["err_yaw"] for a in aim if a]
    if len(ey) < 10:
        return
    row["aim_err_yaw_abs_mean"] = float(np.mean(np.abs(ey)))
    row["aim_err_yaw_abs_median"] = float(np.median(np.abs(ey)))
    row["aim_err_pitch_abs_mean"] = float(np.mean([abs(a["err_pitch"]) for a in aim if a]))

    # pull: does each small rotation reduce the aim error (move toward the target) more often than chance?
    toward, moves, d_list, e_list, ty_d, py_d = 0, 0, [], [], [], []
    for i in range(1, len(frames)):
        a0, a1 = aim[i - 1], aim[i]
        if not a0 or not a1:
            continue
        d = float(_wrap(frames[i]["yaw_exact"] - frames[i - 1]["yaw_exact"]))
        if abs(d) > 0.005:
            moves += 1
            if np.sign(d) == -np.sign(a0["err_yaw"]):
                toward += 1
            d_list.append(d)
            e_list.append(-a0["err_yaw"])
        ty_d.append(float(_wrap(a1["target_yaw"] - a0["target_yaw"])))
        py_d.append(d)
    if moves >= 8:
        row["aim_pull_share"] = toward / moves
        if np.std(d_list) > 0 and np.std(e_list) > 0:
            row["aim_pull_corr"] = float(np.corrcoef(d_list, e_list)[0, 1])
    # tracking lag: which delay (0..6 ticks) best lines up the player's yaw changes with the target's bearing changes
    if len(ty_d) >= 20 and np.std(ty_d) > 0 and np.std(py_d) > 0:
        best, best_c = 0, -2.0
        for lag in range(0, 7):
            a, b = np.asarray(ty_d[: len(ty_d) - lag]), np.asarray(py_d[lag:])
            if len(a) > 5 and np.std(a) > 0 and np.std(b) > 0:
                c = float(np.corrcoef(a, b)[0, 1])
                if c > best_c:
                    best, best_c = lag, c
        row["aim_track_lag_ticks"] = float(best)

    # mouse grid: real mouse rotation moves in whole multiples of one sensitivity step
    steps = [abs(float(frames[i]["yaw_exact"]) - float(frames[i - 1]["yaw_exact"])) for i in range(1, len(frames))
             if frames[i].get("yaw_exact") is not None and frames[i - 1].get("yaw_exact") is not None]
    steps = np.asarray([s for s in steps if 1e-4 < s < 30.0])
    if steps.size >= 10:
        step, on = _mouse_grid(steps)
        row["aim_rot_step"] = step
        row["aim_offgrid_frac"] = 1.0 - on


# smallest yaw step a real mouse can make: sensitivity 0% -> f = 0.2, step = f^3 * 8 * 0.15 degrees
MIN_MOUSE_STEP = 0.2 ** 3 * 8 * 0.15


def _mouse_grid(steps: np.ndarray) -> tuple[float, float]:
    """Best-fitting mouse step and the share of rotation deltas that are whole multiples of it.
    Candidates are the smallest deltas divided by 1..4, never below what a real sensitivity allows (a plain float
    GCD collapses toward float noise). A delta is on the grid when it is within 10% of a step from a whole multiple."""
    small = np.sort(steps)[: min(len(steps), 12)]
    best_step, best_on = NAN, 0.0
    for d in small:
        for k in (1, 2, 3, 4):
            s = float(d) / k
            if s < MIN_MOUSE_STEP:
                continue
            ratio = steps / s
            on = float(np.mean((np.abs(ratio - np.round(ratio)) <= 0.1) & (np.round(ratio) >= 1)))
            if on > best_on + 1e-9:
                best_step, best_on = s, on
    return best_step, best_on


# ---------------------------------------------------------------- triggerbot

def _trigger(row: dict, frames: list[dict], events: list[dict], aim: list) -> None:
    attacks = [e for e in events if e["event_type"] == "attack" and (e.get("g", 0) & 0xFF) == 1]
    if len(attacks) < 3:
        return
    on, entry, cooldown, in_hurt = [], [], [], []
    for e in attacks:
        cooldown.append(e["g"] >> 8)
        k = _frame_index_at(frames, e["tick"])
        if k < 0 or not aim[k]:
            continue
        a = aim[k]
        on.append(abs(a["err_yaw"]) <= a["half_angle"])
        hurt = frames[k].get("opp_hurt_ago_ms")
        if hurt is not None and hurt >= 0:
            in_hurt.append(hurt < 500)
        if on[-1]:
            j = k
            while j > 0 and aim[j - 1] and abs(aim[j - 1]["err_yaw"]) <= aim[j - 1]["half_angle"]:
                j -= 1
            entry.append(k - j)
    if on:
        row["trig_on_target_frac"] = float(np.mean(on))
    if entry:
        row["trig_entry_to_click_ticks_mean"] = _mean(entry)
        row["trig_entry_to_click_ticks_std"] = _std(entry)
    row["trig_cooldown_mean"] = _mean(cooldown)
    row["trig_cooldown_std"] = _std(cooldown)
    if in_hurt:
        row["trig_click_in_hurt_frac"] = float(np.mean(in_hurt))


# ---------------------------------------------------------------- backtrack

def _rtts(events: list[dict]) -> list[tuple[int, float]]:
    """(reply nanos, round trip ms) for every transaction with both its send and its reply in the window."""
    sent = {e["a"]: e["nanos"] for e in events if e["event_type"] == "rtt_sent" and e["b"] == 1}
    out = []
    for e in events:
        if e["event_type"] == "rtt" and e["c"] == 1 and e["a"] in sent:
            out.append((e["nanos"], (e["nanos"] - sent[e["a"]]) / 1e6))
    return sorted(out)


def _backtrack(row: dict, frames: list[dict], events: list[dict], aim: list) -> None:
    rt = _rtts(events)
    if len(rt) >= 5:
        ms = np.asarray([r for _, r in rt])
        row["bt_rtt_mean"] = float(ms.mean())
        row["bt_rtt_std"] = float(ms.std())
        arrive = np.diff([n for n, _ in rt]) / 1e6
        row["bt_pong_burst_frac"] = float(np.mean(arrive < 2.0)) if arrive.size else NAN
        # lag that only shows up with the opponent in reach
        near, far = [], []
        for n, r in rt:
            k = _nearest_frame_by_nanos(frames, events, n)
            if k is not None and aim[k]:
                (near if aim[k]["dist"] <= 3.5 else far).append(r)
        if len(near) >= 3 and len(far) >= 3:
            row["bt_rtt_near_minus_far"] = float(np.mean(near) - np.mean(far))

    attacks = [e for e in events if e["event_type"] == "attack" and (e.get("g", 0) & 0xFF) == 1]
    stale, lags = [], []
    for e in attacks:
        k = _frame_index_at(frames, e["tick"])
        if k < 0 or not aim[k]:
            continue
        # which earlier opponent position was the crosshair pointing at? (0 = the current one)
        errs = [(abs(aim[k - j]["err_yaw"]) if (k - j >= 0 and aim[k - j]) else 1e9) for j in range(0, 8)]
        lags.append(int(np.argmin(errs)))
        stale.append(aim[k]["dist"] > 3.3 and any(aim[k - j] and aim[k - j]["dist"] <= 3.0 for j in range(1, 8) if k - j >= 0))
    if len(lags) >= 3:
        row["bt_implied_lag_ticks_mean"] = _mean(lags)
        row["bt_stale_hit_frac"] = float(np.mean(stale))

    gaps = [f["max_gap_ms"] for f in frames if f.get("max_gap_ms") is not None]
    if len(gaps) >= 20:
        row["bt_gap_p95_ms"] = float(np.percentile(gaps, 95))
        row["bt_burst_mean"] = _mean([f["burst"] for f in frames if f.get("burst") is not None])


def _nearest_frame_by_nanos(frames: list[dict], events: list[dict], nanos: int):
    """Frames carry server ticks, events carry nanos and ticks: map nanos -> tick through the nearest event."""
    best, best_d = None, None
    for e in events:
        d = abs(e["nanos"] - nanos)
        if best_d is None or d < best_d:
            best, best_d = e, d
    if best is None:
        return None
    k = _frame_index_at(frames, best["tick"])
    return k if k >= 0 else None


# ---------------------------------------------------------------- jump reset

def _jump_reset(row: dict, frames: list[dict], events: list[dict]) -> None:
    """Knockback arrival = the reply to the first transaction sent after the knockback packet (client tick of that
    reply). Jump = a jump-key press (input event) on a known client tick."""
    kbs = [e for e in events if e["event_type"] == "knockback" and abs(e["a"]) + abs(e["c"]) > 400]  # horizontal kb
    if not kbs:
        return
    sent = sorted((e["nanos"], e["a"]) for e in events if e["event_type"] == "rtt_sent" and e["b"] == 1)
    replies = {e["a"]: e for e in events if e["event_type"] == "rtt" and e["c"] == 1}
    presses, prev = [], 0
    for e in sorted((e for e in events if e["event_type"] == "input"), key=lambda e: e["nanos"]):
        if e["a"] & 16 and not prev & 16 and e.get("client_tick") is not None:
            presses.append(e["client_tick"])
        prev = e["a"]
    offsets, considered = [], 0
    for kb in kbs:
        after = next((tid for n, tid in sent if n >= kb["nanos"]), None)
        rep = replies.get(after) if after is not None else None
        if rep is None or rep.get("client_tick") is None:
            continue
        k = _frame_index_at(frames, kb["tick"])
        if k < 0 or not frames[k].get("on_ground"):
            continue
        considered += 1
        arrive = rep["client_tick"]
        near = [p - arrive for p in presses if -2 <= p - arrive <= 4]
        if near:
            offsets.append(min(near, key=abs))
    row["jr_knockbacks"] = float(considered)
    if considered >= 2:
        row["jr_reset_rate"] = len([o for o in offsets if -1 <= o <= 2]) / considered
    if len(offsets) >= 2:
        row["jr_offset_mean"] = _mean(offsets)
        row["jr_offset_std"] = _std(offsets)
        row["jr_offset_zero_frac"] = float(np.mean(np.asarray(offsets) == 0))


# ---------------------------------------------------------------- W-tap

def _wtap(row: dict, events: list[dict]) -> None:
    ev = sorted((e for e in events if e["event_type"] in ("attack", "input", "move_state")), key=lambda e: e["nanos"])
    attacks = [e for e in ev if e["event_type"] == "attack" and (e.get("g", 0) & 0xFF) == 1]
    if len(attacks) < 3:
        return
    taps, react, hold, same = 0, [], [], []
    for a in attacks:
        rel = release = None
        w_on = None
        for e in ev:
            if e["nanos"] <= a["nanos"]:
                if e["event_type"] == "input":
                    w_on = bool(e["a"] & 1)
                continue
            if e["nanos"] - a["nanos"] > 500e6:
                break
            if rel is None:
                if (e["event_type"] == "input" and w_on and not e["a"] & 1) or (e["event_type"] == "move_state" and e["a"] == 2):
                    rel = e
            elif (e["event_type"] == "input" and e["a"] & 1) or (e["event_type"] == "move_state" and e["a"] == 1):
                release = e
                break
            if e["event_type"] == "input":
                w_on = bool(e["a"] & 1)
        if rel is None:
            continue
        taps += 1
        react.append((rel["nanos"] - a["nanos"]) / 1e6)
        if a.get("client_tick") is not None and rel.get("client_tick") is not None:
            same.append(rel["client_tick"] == a["client_tick"])
        if release is not None:
            hold.append((release["nanos"] - rel["nanos"]) / 1e6)
    row["wtap_rate"] = taps / len(attacks)
    if react:
        row["wtap_reaction_ms_mean"] = _mean(react)
        row["wtap_reaction_ms_std"] = _std(react)
    if hold:
        row["wtap_hold_ms_mean"] = _mean(hold)
        row["wtap_hold_ms_std"] = _std(hold)
    if same:
        row["wtap_same_tick_frac"] = float(np.mean(same))


# ---------------------------------------------------------------- clicks, crystals, anchors, shields

def _click(row: dict, events: list[dict]) -> None:
    t = np.sort([e["nanos"] for e in events if e.get("event_type") == "swing" and e.get("nanos") is not None])
    gaps = np.diff(t) / 1e6 if t.size >= 2 else np.empty(0)
    gaps = gaps[(gaps > 0) & (gaps < 1000)]
    if gaps.size < 8:
        return
    sd = gaps.std()
    if sd > 0:
        row["click_gap_kurtosis"] = float(np.mean(((gaps - gaps.mean()) / sd) ** 4) - 3.0)
    hist, _ = np.histogram(gaps, bins=np.arange(0, 1001, 10))
    p = hist[hist > 0] / gaps.size
    row["click_gap_entropy"] = float(-(p * np.log2(p)).sum())
    row["click_double_frac"] = float(np.mean(gaps < 50))


def _crystal_anchor(row: dict, events: list[dict]) -> None:
    spawns = {e["a"]: e["nanos"] for e in events if e["event_type"] == "entity_spawn" and e["b"] == 1}
    breaks = [(e["nanos"] - spawns[e["b"]]) / 1e6 for e in events
              if e["event_type"] == "attack" and (e.get("g", 0) & 0xFF) == 2 and e["b"] in spawns]
    row["crystal_breaks"] = float(len(breaks))
    if len(breaks) >= 2:
        row["crystal_break_ms_mean"] = _mean(breaks)
        row["crystal_break_ms_std"] = _std(breaks)
    anchors = sorted(e["nanos"] for e in events if e["event_type"] == "block_place" and (e.get("g", 0) & 0xFF) == 1)
    row["anchor_clicks"] = float(len(anchors))
    if len(anchors) >= 3:
        row["anchor_gap_ms_std"] = _std(np.diff(anchors) / 1e6)


def _shield(row: dict, frames: list[dict], events: list[dict]) -> None:
    """Swap to an axe while the opponent is blocking: how long after they raised the shield."""
    rx = []
    for e in events:
        # hotbar_swap: c = previous category | new category << 8
        if e["event_type"] != "hotbar_swap" or ((e["c"] >> 8) & 0xFF) != AXE:
            continue
        k = _frame_index_at(frames, e["tick"])
        if k < 0 or frames[k].get("opp_state") is None:
            continue
        f = frames[k]
        if f["opp_state"] & R.OPP_BLOCKING and f.get("opp_block_ago_ms", -1) >= 0:
            rx.append(f["opp_block_ago_ms"])
    row["shield_axe_swaps"] = float(len(rx))
    if len(rx) >= 2:
        row["shield_axe_reaction_ms_mean"] = _mean(rx)
        row["shield_axe_reaction_ms_std"] = _std(rx)
