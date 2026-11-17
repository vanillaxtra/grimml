"""Spear-mace features: Instant Lunge, Auto Mace and Auto Stun Slam.

Every technique these modules automate is also done by hand (lunge swaps, attribute swaps, stun slams), so the features
measure timing *distributions* - how fast and how regular the swap -> hit -> swap back chain is - not whether it
happened. Detector port: detector/.../features/Schema4Features.java (spearMace / maceAim), checked by ParitySelfCheck.

What the recordings carry (checked on real windows):
  * a spear jab is NOT an attack packet: 1.21.11 sends a digging packet with action STAB, recorded as a `break` event
    with a = 8 (PacketEvents DiggingAction ordinal), plus a swing
  * recorders before the ItemCategories fix wrote spears as category 0; their item id (FNV-1a of the item key) still
    says which spear it was, so categories are resolved through SPEAR_IDS
  * `fall_milli` is the drop below the last ground y, not the fall from the apex (0 after a wind charge launch), so
    the mace fall is measured from y_milli and on_ground

Runs on schema 3 and 4 windows (hotbar swaps, attacks, digs and positions exist in both); the client-tick parts need
schema 4 and stay NaN without it. Training with --schema4-features only ever sees schema 4 windows.
"""
from __future__ import annotations

from collections import Counter

import numpy as np

NAN = float("nan")

AXE, MACE, SPEAR = 2, 3, 4
STAB = 8  # digging action

LUNGE_SWAP_IN_MS = 150.0
MACE_SWAP_IN_MS = 500.0
SWAP_BACK_MS = 1000.0
LUNGE_INTERVAL_MS = 2000.0
STUN_GAP_MS = 500.0
SNAP_DEG = 10.0

SPEAR_MACE_FEATURES = [
    "swap_order_violations",
    "lunge_stabs", "lunge_swap_in_frac", "lunge_swap_stab_ms_mean", "lunge_swap_stab_ms_std",
    "lunge_back_ms_mean", "lunge_back_ms_std", "lunge_back_ticks_mode_frac", "lunge_interval_ms_mean",
    "lunge_interval_cv",
    "mace_hits", "mace_fall_mean", "mace_apex_ticks_mean", "mace_land_tick_frac", "mace_swap_in_frac",
    "mace_swap_hit_ms_mean", "mace_swap_hit_ms_std", "mace_back_ms_mean", "mace_back_ms_std",
    "mace_snap_deg_mean", "mace_snapback_frac", "mace_aim_err_mean",
    "stun_slams", "stun_gap_ms_mean", "stun_gap_ms_std", "stun_gap_ticks_mode_frac", "stun_axe_swap_hit_ms_mean",
    "stun_axe_to_mace_swap_ms_mean", "stun_block_frac",
]


def item_id(key: str) -> int:
    """The recorder's ItemCategories.nameId: 32-bit FNV-1a of the item key, as a signed int."""
    h = 0x811C9DC5
    for ch in key:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


SPEAR_IDS = frozenset(item_id(m + "_spear") for m in
                      ("wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"))


def resolve_cat(cat, iid) -> int:
    """Item category with spears recognised by item id (old recordings wrote them as 0)."""
    if iid is not None and iid in SPEAR_IDS:
        return SPEAR
    return int(cat or 0)


# ---------------------------------------------------------------- the action stream

SWAP, HIT, STAB_ACT = 1, 2, 3


def actions(events: list[dict]) -> list[dict]:
    """Hotbar swaps, player hits and spear stabs in event order, categories resolved.
    Offhand swaps (slot 40) are not hotbar swaps. A hit is an attack on a player: kind 1 in g (schema 4), or a known
    target when g is absent (schema 3)."""
    out = []
    for e in events:
        t = e.get("event_type")
        ct = e.get("client_tick") or 0
        if t == "hotbar_swap":
            if e["a"] == 40:
                continue
            c = e["c"]
            out.append({"k": SWAP, "n": e["nanos"], "ct": ct,
                        "from": resolve_cat(c & 0xFF, e.get("d")), "to": resolve_cat((c >> 8) & 0xFF, e.get("e"))})
        elif t == "attack":
            g = e.get("g") or 0
            if (g & 0xFF) != 1 and not (g == 0 and e["a"] != 0):
                continue
            out.append({"k": HIT, "n": e["nanos"], "ct": ct, "tick": e["tick"], "target": e["b"],
                        "cat": resolve_cat((e["c"] >> 8) & 0xFF, e.get("e"))})
        elif t == "break" and e["a"] == STAB:
            out.append({"k": STAB_ACT, "n": e["nanos"], "ct": ct, "tick": e["tick"], "cat": SPEAR})
    return out


def _swap_before(acts: list[dict], i: int):
    for j in range(i - 1, -1, -1):
        if acts[j]["k"] == SWAP:
            return acts[j]
    return None


def _swap_after(acts: list[dict], i: int):
    for j in range(i + 1, len(acts)):
        if acts[j]["k"] == SWAP:
            return acts[j]
    return None


def _ms(a: dict, b: dict) -> float:
    return (b["n"] - a["n"]) / 1e6


def _mean2(x) -> float:
    return float(np.mean(x)) if len(x) >= 2 else NAN


def _std2(x) -> float:
    return float(np.std(x)) if len(x) >= 2 else NAN


def _mode_frac(x: list[int]) -> float:
    """Share of values equal to the most common one (ties: the smallest value). Needs 3 values."""
    if len(x) < 3:
        return NAN
    c = Counter(x)
    best = min(c, key=lambda v: (-c[v], v))
    return c[best] / len(x)


def _frame_index_at(frames: list[dict], tick: int) -> int:
    lo, hi, best = 0, len(frames) - 1, -1
    while lo <= hi:
        mid = (lo + hi) // 2
        if frames[mid]["tick"] <= tick:
            best = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return best


# ---------------------------------------------------------------- features without aim data

def spear_mace_features(frames: list[dict], events: list[dict]) -> dict:
    row = {k: NAN for k in SPEAR_MACE_FEATURES}
    acts = actions(events)
    _order(row, acts)
    _lunge(row, acts)
    _mace(row, frames, acts)
    _stun(row, frames, acts)
    return row


def _order(row: dict, acts: list[dict]) -> None:
    """Vanilla sends hotbar changes before attacks inside one client tick: a swap after a hit/stab in the same
    client tick cannot come from an unmodified client."""
    if not any(a["ct"] > 0 for a in acts):
        return
    n, last_hit_ct = 0, -1
    for a in acts:
        if a["k"] == SWAP:
            if a["ct"] > 0 and a["ct"] == last_hit_ct:
                n += 1
        else:
            last_hit_ct = a["ct"]
    row["swap_order_violations"] = float(n)


def _lunge(row: dict, acts: list[dict]) -> None:
    idx = [i for i, a in enumerate(acts) if a["k"] == STAB_ACT]
    row["lunge_stabs"] = float(len(idx))
    swapped, sw_ms, back_ms, back_ticks, gaps = 0, [], [], [], []
    for i in idx:
        s = _swap_before(acts, i)
        if s is not None and s["to"] == SPEAR and _ms(s, acts[i]) <= LUNGE_SWAP_IN_MS:
            swapped += 1
            sw_ms.append(_ms(s, acts[i]))
        b = _swap_after(acts, i)
        if b is not None and b["from"] == SPEAR and _ms(acts[i], b) <= SWAP_BACK_MS:
            back_ms.append(_ms(acts[i], b))
            if b["ct"] > 0 and acts[i]["ct"] > 0:
                back_ticks.append(b["ct"] - acts[i]["ct"])
    for p, q in zip(idx, idx[1:]):
        d = _ms(acts[p], acts[q])
        if d <= LUNGE_INTERVAL_MS:
            gaps.append(d)
    if len(idx) >= 2:
        row["lunge_swap_in_frac"] = swapped / len(idx)
    row["lunge_swap_stab_ms_mean"] = _mean2(sw_ms)
    row["lunge_swap_stab_ms_std"] = _std2(sw_ms)
    row["lunge_back_ms_mean"] = _mean2(back_ms)
    row["lunge_back_ms_std"] = _std2(back_ms)
    row["lunge_back_ticks_mode_frac"] = _mode_frac(back_ticks)
    if len(gaps) >= 3:
        m = float(np.mean(gaps))
        row["lunge_interval_ms_mean"] = m
        if m > 0:
            row["lunge_interval_cv"] = float(np.std(gaps)) / m


def _fall(frames: list[dict], k: int) -> tuple[float, int] | None:
    """(blocks fallen since the apex, ticks since the apex) at frame k, looking back to the last on-ground frame."""
    if frames[k].get("y_milli") is None:
        return None
    apex, ai, j = frames[k]["y_milli"], k, k
    while j >= 0:
        y = frames[j].get("y_milli")
        if y is not None and y > apex:
            apex, ai = y, j
        if frames[j].get("on_ground"):
            break
        j -= 1
    return (apex - frames[k]["y_milli"]) / 1000.0, frames[k]["tick"] - frames[ai]["tick"]


def _mace(row: dict, frames: list[dict], acts: list[dict]) -> None:
    idx = [i for i, a in enumerate(acts) if a["k"] == HIT and a["cat"] == MACE]
    row["mace_hits"] = float(len(idx))
    if len(idx) < 2:
        return
    falls, apex, land, swapped, sw_ms, back_ms = [], [], [], 0, [], []
    for i in idx:
        k = _frame_index_at(frames, acts[i]["tick"])
        if k >= 0:
            fa = _fall(frames, k)
            if fa is not None:
                falls.append(fa[0])
                apex.append(fa[1])
            land.append(bool(not frames[k].get("on_ground") and k + 1 < len(frames)
                             and frames[k + 1].get("on_ground")))
        s = _swap_before(acts, i)
        if s is not None and s["to"] == MACE and _ms(s, acts[i]) <= MACE_SWAP_IN_MS:
            swapped += 1
            sw_ms.append(_ms(s, acts[i]))
        b = _swap_after(acts, i)
        if b is not None and b["from"] == MACE and _ms(acts[i], b) <= SWAP_BACK_MS:
            back_ms.append(_ms(acts[i], b))
    row["mace_fall_mean"] = _mean2(falls)
    row["mace_apex_ticks_mean"] = _mean2(apex)
    if len(land) >= 2:
        row["mace_land_tick_frac"] = float(np.mean(land))
    row["mace_swap_in_frac"] = swapped / len(idx)
    row["mace_swap_hit_ms_mean"] = _mean2(sw_ms)
    row["mace_swap_hit_ms_std"] = _std2(sw_ms)
    row["mace_back_ms_mean"] = _mean2(back_ms)
    row["mace_back_ms_std"] = _std2(back_ms)


def _stun(row: dict, frames: list[dict], acts: list[dict]) -> None:
    gaps, ticks, axe_sw, to_mace, block = [], [], [], [], []
    for i, a in enumerate(acts):
        if a["k"] != HIT or a["cat"] != AXE:
            continue
        nxt = next((j for j in range(i + 1, len(acts)) if acts[j]["k"] != SWAP), None)
        if nxt is None:
            continue
        m = acts[nxt]
        if m["k"] != HIT or m["cat"] != MACE or m["target"] != a["target"] or _ms(a, m) > STUN_GAP_MS:
            continue
        gaps.append(_ms(a, m))
        if a["ct"] > 0 and m["ct"] > 0:
            ticks.append(m["ct"] - a["ct"])
        s = _swap_before(acts, i)
        if s is not None and s["to"] == AXE and _ms(s, a) <= MACE_SWAP_IN_MS:
            axe_sw.append(_ms(s, a))
        sw = next((acts[j] for j in range(i + 1, nxt) if acts[j]["to"] == MACE), None)
        if sw is not None:
            to_mace.append(_ms(a, sw))
        k = _frame_index_at(frames, a["tick"]) - 1
        if k >= 0 and frames[k].get("opp_state") is not None:
            block.append(bool(frames[k]["opp_state"] & 8))
    row["stun_slams"] = float(len(gaps))
    row["stun_gap_ms_mean"] = _mean2(gaps)
    row["stun_gap_ms_std"] = _std2(gaps)
    row["stun_gap_ticks_mode_frac"] = _mode_frac(ticks)
    row["stun_axe_swap_hit_ms_mean"] = _mean2(axe_sw)
    row["stun_axe_to_mace_swap_ms_mean"] = _mean2(to_mace)
    if len(block) >= 2:
        row["stun_block_frac"] = float(np.mean(block))


# ---------------------------------------------------------------- aim on the mace hit (schema 4)

def mace_aim_features(frames: list[dict], events: list[dict], aim: list) -> dict:
    """Silent aim on a mace hit: the rotation snaps toward the target on the hit tick and back on the next one."""
    out = {"mace_snap_deg_mean": NAN, "mace_snapback_frac": NAN, "mace_aim_err_mean": NAN}
    snap, back, err = [], [], []
    for a in actions(events):
        if a["k"] != HIT or a["cat"] != MACE:
            continue
        k = _frame_index_at(frames, a["tick"])
        if k < 0:
            continue
        if aim[k]:
            err.append(abs(aim[k]["err_yaw"]))
        if k >= 1 and k + 1 < len(frames) and all(frames[j].get("yaw_exact") is not None for j in (k - 1, k, k + 1)):
            din = _wrap(frames[k]["yaw_exact"] - frames[k - 1]["yaw_exact"])
            dout = _wrap(frames[k + 1]["yaw_exact"] - frames[k]["yaw_exact"])
            snap.append(abs(din))
            back.append(abs(din) > SNAP_DEG and abs(dout) > SNAP_DEG and (din > 0) != (dout > 0))
    out["mace_snap_deg_mean"] = _mean2(snap)
    if len(back) >= 2:
        out["mace_snapback_frac"] = float(np.mean(back))
    out["mace_aim_err_mean"] = _mean2(err)
    return out


def _wrap(deg: float) -> float:
    return (float(deg) + 180.0) % 360.0 - 180.0


# ---------------------------------------------------------------- live lunge checks (LungeRate, LungeHold, SwapOrder, AxeHold)

RATE, HOLD, ORDER, AXE_HOLD = 1, 2, 4, 8  # alert bits returned by LiveLunge


class LiveLunge:
    """The detector's three live lunge checks, event by event (Java: dev.grimml.detect.features.LungeRate).

    A swap-lunge is a spear STAB whose last hotbar swap went to the spear at most `swap_in_ms` earlier (each swap counts
    once). Holding the spear and jabbing without swaps never counts.

    RATE (LungeRate): by hand the chain swap -> jab -> swap back takes time: legit swap-lunges were never under 345 ms
    apart (3 sessions, 2 players). Gaps in [min_ms, fast_ms) between consecutive swap-lunges are counted; `count` of
    them inside `window_ms` alert. Gaps under min_ms are packets bunched by lag and do not count.

    HOLD (LungeHold): the spear held for exactly one client tick (swap in, jab, swap back on the next tick). Legit 1.1%
    of swap-lunges, Instant Lunge 34-100% at 0-90 ms delay. `hold_count` one-tick holds among the last `hold_of`
    swap-lunges alert. Works however slowly the lunges are chained.

    ORDER (SwapOrder): a hotbar swap in the same client tick after an attack or STAB. Vanilla sends hotbar changes
    before attacks inside one client tick, so this never happens by hand (0 in ~1400 legit hits and swaps); a client
    doing the whole chain in one tick does it on every lunge. `order_count` inside `order_window_ms` alert.

    AXE_HOLD (AxeHold): Auto Shield Breaker swaps to the axe, hits the shield and swaps away on the very next client
    tick. An axe hold is a hotbar swap to the axe with at least one hit on a player, ended by the swap away from it;
    it is one-tick when that swap comes one client tick after the last hit. Legit fast hand stuns against a shielding
    bot: 3-8% one-tick (3 sessions, ~220 axe holds); Prestige Auto Shield Breaker 63-98% (6 sessions, every setting).
    `axe_count` one-tick holds among the last `axe_of` axe holds alert. Grim's breaker (2 ticks) is not covered.

    The tick rules need client ticks (1.21.2+ clients, ct > 0). All times are integer nanoseconds, so Python and Java
    agree exactly. Each feed returns the alert bits (0 = none); a check's counter restarts when it alerts.
    """

    def __init__(self, fast_ms: float = 300.0, min_ms: float = 75.0, count: int = 6, window_ms: float = 30000.0,
                 swap_in_ms: float = LUNGE_SWAP_IN_MS, hold_count: int = 5, hold_of: int = 10, order_count: int = 2,
                 order_window_ms: float = 60000.0, axe_count: int = 6, axe_of: int = 10):
        self.fast_ns = int(fast_ms * 1e6)
        self.min_ns = int(min_ms * 1e6)
        self.count = int(count)
        self.window_ns = int(window_ms * 1e6)
        self.swap_in_ns = int(swap_in_ms * 1e6)
        self.hold_count = int(hold_count)
        self.hold_of = max(1, int(hold_of))
        self.order_count = int(order_count)
        self.order_window_ns = int(order_window_ms * 1e6)
        self.swap_to = -1
        self.swap_ns = 0
        self.swap_ct = 0
        self.swap_used = True
        self.last_lunge = None
        self.fast: list[int] = []
        self.lunges = 0
        self.pending_hold_ct = -1      # client tick of the swap-in of the last swap-lunge, until the next swap
        self.holds: list[int] = []     # last hold_of swap-lunges: 1 = held one client tick
        self.hit_ct = 0                # client tick of the last attack / STAB
        self.order: list[int] = []
        self.order_total = 0
        self.axe_count = int(axe_count)
        self.axe_of = max(1, int(axe_of))
        self.axe_hit_ct = -1           # client tick of the last hit while holding the axe swapped in, until the next swap
        self.axes: list[int] = []      # last axe_of axe holds: 1 = swapped away one client tick after the last hit
        self.axe_holds = 0

    def swap(self, nanos: int, to_cat: int, from_cat: int = -1, ct: int = 0) -> int:
        nanos, ct = int(nanos), int(ct)
        out = 0
        if ct > 0 and self.hit_ct == ct:
            self.order_total += 1
            self.order.append(nanos)
            while self.order and nanos - self.order[0] > self.order_window_ns:
                self.order.pop(0)
            if len(self.order) >= self.order_count:
                self.order.clear()
                out |= ORDER
        if self.pending_hold_ct >= 0:
            if from_cat == SPEAR and ct > 0 and self.pending_hold_ct > 0:
                self.holds.append(1 if ct - self.pending_hold_ct == 1 else 0)
                if len(self.holds) > self.hold_of:
                    self.holds.pop(0)
                if sum(self.holds) >= self.hold_count:
                    self.holds.clear()
                    out |= HOLD
            self.pending_hold_ct = -1
        if self.axe_hit_ct > 0:
            if from_cat == AXE and ct > 0:
                self.axe_holds += 1
                self.axes.append(1 if ct - self.axe_hit_ct == 1 else 0)
                if len(self.axes) > self.axe_of:
                    self.axes.pop(0)
                if sum(self.axes) >= self.axe_count:
                    self.axes.clear()
                    out |= AXE_HOLD
            self.axe_hit_ct = -1
        self.swap_to = int(to_cat)
        self.swap_ns = nanos
        self.swap_ct = ct
        self.swap_used = False
        return out

    def hit(self, nanos: int, ct: int = 0) -> int:
        self.hit_ct = int(ct)
        if self.swap_to == AXE and self.hit_ct > 0:
            self.axe_hit_ct = self.hit_ct
        return 0

    def stab(self, nanos: int, ct: int = 0) -> int:
        """Feeds one STAB; returns RATE when LungeRate alerts."""
        nanos = int(nanos)
        self.hit_ct = int(ct)
        if self.swap_used or self.swap_to != SPEAR or nanos - self.swap_ns > self.swap_in_ns:
            return 0
        self.swap_used = True
        self.lunges += 1
        self.pending_hold_ct = self.swap_ct
        prev, self.last_lunge = self.last_lunge, nanos
        if prev is None:
            return 0
        gap = nanos - prev
        if self.min_ns <= gap < self.fast_ns:
            self.fast.append(nanos)
        while self.fast and nanos - self.fast[0] > self.window_ns:
            self.fast.pop(0)
        if len(self.fast) >= self.count:
            self.fast.clear()
            return RATE
        return 0

    def feed(self, a: dict) -> int:
        """Feeds one action from actions()."""
        ct = a.get("ct") or 0
        if a["k"] == SWAP:
            return self.swap(a["n"], a["to"], a["from"], ct)
        if a["k"] == STAB_ACT:
            return self.stab(a["n"], ct)
        if a["k"] == HIT:
            return self.hit(a["n"], ct)
        return 0

    @property
    def recent_fast(self) -> int:
        return len(self.fast)
