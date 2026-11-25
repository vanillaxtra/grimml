"""Flick family: how the view turns around each attack (schema 4 frames + attack events).

Silent aim, knockback displacement and snap kill auras turn the view onto the target on the hit tick and turn it
back right after. A real player's flick builds up and slows down over several ticks and then keeps tracking.
For every attack with frames from 3 client ticks before to 3 after:
  into  = turn over the 3 ticks into the hit      back = turn over the 3 ticks after it
  d0    = turn on the hit tick itself
A hit is a "flick" when |into| >= FLICK_MIN_DEG. Shares are over all attacks with full frames:
  flick_one_tick_frac  flicks where the hit tick carries > ONE_TICK_SHARE of the turn
  flick_return_frac    flicks where > RETURN_SHARE of the turn goes back afterwards
  flick_snap_frac      flicks that do both (the silent-aim signature)
Prototype 2026-11-25 (16 legit fights vs 7 silent aim sessions): one-tick share was 0.6-7.5% legit, 12-31% silent aim.
Java port: detector/.../features/Flick.java. Same numbers; ParitySelfCheck compares both. Change both together.
"""
from __future__ import annotations

NAN = float("nan")
FLICK_FEATURES = ["flick_one_tick_frac", "flick_return_frac", "flick_snap_frac"]
FLICK_MIN_DEG = 8.0
ONE_TICK_SHARE = 0.8
RETURN_SHARE = 0.6
SPAN = 3


def _wrap(deg: float) -> float:
    m = (deg + 180.0) % 360.0
    return m - 180.0


def flick_features(frames: list[dict], events: list[dict]) -> dict:
    row = {k: NAN for k in FLICK_FEATURES}
    yaw_at: dict[int, float | None] = {}
    for f in frames:
        ct = f.get("client_tick")
        if ct:  # None/0 = unknown (the parity fixture writes None as 0)
            yaw_at[ct] = f.get("yaw_exact")  # last frame of each client tick
    n = one = ret = snap = 0
    for e in events:
        if e.get("event_type") != "attack":
            continue
        ct = e.get("client_tick")
        if not ct:
            continue
        ys = [yaw_at.get(ct + k) for k in range(-SPAN, SPAN + 1)]
        if any(y is None for y in ys):
            continue
        n += 1
        into = _wrap(ys[SPAN] - ys[0])
        if abs(into) < FLICK_MIN_DEG:
            continue
        d0 = _wrap(ys[SPAN] - ys[SPAN - 1])
        back = _wrap(ys[2 * SPAN] - ys[SPAN])
        o = abs(d0) > ONE_TICK_SHARE * abs(into)
        r = -back / into > RETURN_SHARE
        one += o
        ret += r
        snap += o and r
    if n:
        row["flick_one_tick_frac"] = one / n
        row["flick_return_frac"] = ret / n
        row["flick_snap_frac"] = snap / n
    return row
