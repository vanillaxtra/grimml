"""Mouse-grid aim check: real mouse rotation moves in whole multiples of one sensitivity step.

Minecraft turns mouse movement into rotation as  delta = pixels * f^3 * 8 * 0.15  with  f = sensitivity * 0.6 + 0.2,
on yaw and pitch alike. Aim assist, aimbots and kill aura add rotation that is not a whole number of steps.

learn_step()   finds the player's step from rotation deltas. Candidates come from the formula above (never a free
               float GCD, which collapses toward float noise), the LARGEST step that fits almost as well as the best
               wins (step/2 and step/3 fit too), then a least-squares refinement pins it from larger deltas.
off_grid()     is one delta off the grid of a step?
grid_features() window features (ML): off-grid share overall, toward the opponent and away from it.
Cusum          the live fight-level sequential test (detector AimGrid check) over off-grid indicators.

The detector has an exact Java port (dev.grimml.detect.features.AimGrid); the parity fixture checks them.
"""
from __future__ import annotations

import math

import numpy as np

NAN = float("nan")
TOL = 0.10            # a delta is on the grid within 10% of a step of a whole multiple (yaw is a float: at |yaw| ~ 16k
                      # one float step is ~2% of a typical mouse step)
MAX_DELTA = 30.0      # bigger jumps are teleports / respawns, not mouse movement
MIN_DELTA = 1e-4
MIN_SAMPLES = 30      # fewer deltas: no step
MIN_FIT = 0.5         # best step must put at least half the deltas on the grid, else abstain (controller, cinematic)
COARSE_N = 4001       # sensitivity 0..1 (0..200% in game) in steps of 0.00025
SMALLEST = 96         # coarse search uses the first 96 deltas under COARSE_MAX, in time order (the smallest
                      # would be the cheat's micro-corrections)
COARSE_MAX = 2.0
NEAR_BEST = 0.9       # largest candidate scoring >= 90% of the best is the step

GRID_FEATURES = ["grid_off_frac", "grid_off_toward", "grid_off_away", "grid_toward_minus_away"]


def step_for(sens: float) -> float:
    f = sens * 0.6 + 0.2
    return f * f * f * 8.0 * 0.15


MIN_STEP = step_for(0.0)
COARSE_STEPS = np.array([step_for(i / (COARSE_N - 1)) for i in range(COARSE_N)], dtype=np.float64)


def _ratio_off(d: float, step: float) -> bool:
    r = d / step
    n = math.floor(r + 0.5)
    return n < 1 or abs(r - n) > TOL


def off_grid(d: float, step: float) -> bool:
    return _ratio_off(abs(d), step)


def _on_share(ds: list[float], step: float) -> float:
    on = 0
    for d in ds:
        if not _ratio_off(d, step):
            on += 1
    return on / len(ds)


def learn_step(deltas: list[float]) -> tuple[float, float]:
    """(step, share of deltas on its grid), or (nan, share) when no step fits. deltas: absolute, already filtered."""
    ds = [d for d in deltas if MIN_DELTA < d < MAX_DELTA]
    if len(ds) < MIN_SAMPLES:
        return NAN, 0.0
    small = np.asarray([d for d in ds if d < COARSE_MAX][:SMALLEST], dtype=np.float64)  # time order, not smallest
    if small.size < MIN_SAMPLES // 2:
        return NAN, 0.0
    r = small[None, :] / COARSE_STEPS[:, None]          # same IEEE ops as the Java loop
    n = np.floor(r + 0.5)
    on = np.sum((n >= 1) & (np.abs(r - n) <= TOL), axis=1)
    best = int(on.max())
    if best <= 0:
        return NAN, 0.0
    # ascending steps: the largest that is near the best (step/2, step/3 fit as well as the true step)
    idx = np.nonzero(on >= NEAR_BEST * best)[0]
    step = float(COARSE_STEPS[idx[-1]])
    # refine: least squares on on-grid deltas, widening from small multiples to all
    for limit, tol in ((20.0, 0.25), (20.0, TOL / 2), (100.0, TOL / 2), (400.0, TOL / 2), (1e9, TOL / 2)):
        num = den = 0.0
        for d in ds:
            r = d / step
            n = math.floor(r + 0.5)
            if 1 <= n <= limit and abs(r - n) <= tol:
                num += d * n
                den += float(n) * n
        if den > 0:
            step = num / den
    fit = _on_share(ds, step)
    if fit < MIN_FIT:
        return NAN, fit
    return step, fit


def rotation_deltas(frames: list[dict]) -> tuple[list[float], list[tuple[int, float]]]:
    """All |deltas| (yaw and pitch) between consecutive ticks with exact rotation, and the yaw deltas with their
    frame index (for toward/away). Pitch at the +-90 clamp is skipped."""
    allv: list[float] = []
    yaw: list[tuple[int, float]] = []
    for i in range(1, len(frames)):
        a, b = frames[i - 1], frames[i]
        if a.get("yaw_exact") is None or b.get("yaw_exact") is None or b["tick"] - a["tick"] != 1:
            continue
        dy = float(b["yaw_exact"]) - float(a["yaw_exact"])
        if MIN_DELTA < abs(dy) < MAX_DELTA:
            allv.append(abs(dy))
            yaw.append((i, dy))
        if abs(float(b["pitch_exact"])) < 89.0 and abs(float(a["pitch_exact"])) < 89.0:
            dp = abs(float(b["pitch_exact"]) - float(a["pitch_exact"]))
            if MIN_DELTA < dp < MAX_DELTA:
                allv.append(dp)
    return allv, yaw


def grid_features(frames: list[dict], aim: list) -> dict:
    """aim: per frame {'err_yaw': ...} or None (schema4._aim_track). Toward = the yaw delta shrinks |aim error|."""
    row = {k: NAN for k in GRID_FEATURES}
    allv, yaw = rotation_deltas(frames)
    step, _ = learn_step(allv)
    if math.isnan(step):
        return row
    ds = [d for d in allv if MIN_DELTA < d < MAX_DELTA]
    row["grid_off_frac"] = 1.0 - _on_share(ds, step)
    tw = [0, 0]
    aw = [0, 0]
    for i, dy in yaw:
        prev = aim[i - 1]
        if not prev:
            continue
        toward = (dy < 0) == (prev["err_yaw"] > 0)   # error = yaw - target: moving against its sign closes it
        bucket = tw if toward else aw
        bucket[0] += 1
        if off_grid(dy, step):
            bucket[1] += 1
    if tw[0] >= 10:
        row["grid_off_toward"] = tw[1] / tw[0]
    if aw[0] >= 10:
        row["grid_off_away"] = aw[1] / aw[0]
    if tw[0] >= 10 and aw[0] >= 10:
        row["grid_toward_minus_away"] = row["grid_off_toward"] - row["grid_off_away"]
    return row


class Cusum:
    """One-sided CUSUM over off-grid indicators: legit rate p0, cheat rate p1. Alerts when the score reaches h."""

    def __init__(self, p0: float, p1: float, h: float):
        self.up = math.log(p1 / p0)
        self.down = math.log((1.0 - p1) / (1.0 - p0))
        self.h = h
        self.s = 0.0

    def add(self, off: bool) -> bool:
        self.s = max(0.0, self.s + (self.up if off else self.down))
        return self.s >= self.h


class LiveGrid:
    """The detector's AimGrid check, frame by frame (Java: dev.grimml.detect.features.AimGrid.Live).

    The step is learned from ALL rotation (out of fights the assist has no target, so that rotation is clean, and the
    step is known before the first fight); only rotation while the player has an opponent feeds the test. The step
    is learned from the first LEARN samples and
    re-checked every CHECK samples on the latest CHECK: no step fits (cinematic camera, controller) -> abstain until
    one fits again; a clearly different step (sensitivity changed) -> adopt it and restart. An alert needs the CUSUM
    over off-grid samples to reach h AND off-grid yaw to lean toward the opponent (aim assist pulls toward the target;
    zoom, cinematic camera and odd mice are off-grid in both directions).

    Per-player baseline: mouse acceleration (Windows "Enhance pointer precision", Raw Accel, custom curves) puts some
    legit rotation off the grid. The assist only acts with a target, so rotation outside fights shows the player's own
    off-grid rate. The last BASE_KEEP out-of-fight samples (on the current step) give that rate; the test's legit rate
    becomes max(p0, rate * base_margin) (rounded to 0.001) and its cheat rate max(p1, 2 * that). A player whose rate is
    over base_max is skipped (their mouse cannot be told from an assist). Until base_min out-of-fight samples exist,
    fight rotation is not tested at all. Moves over max_mult steps are ignored (baseline and test): a tiny error in the
    learned step grows with the size of the move, and acceleration curves bend fast flicks the most, while an assist's
    corrections are small."""

    LEARN = 200
    CHECK = 200
    KEEP = 600
    RING = 300
    MIN_SIDE = 10
    STEP_CHANGE = 0.005
    BASE_KEEP = 600

    def __init__(self, p0: float = 0.01, p1: float = 0.10, h: float = 20.0, min_bias: float = 0.05,
                 base_margin: float = 1.5, base_max: float = 0.08, base_min: int = 100, max_mult: float = 20.0):
        self.max_mult = max_mult
        self.p0, self.p1 = p0, p1
        self.cusum = Cusum(p0, p1, h)
        self.min_bias = min_bias
        self.base_margin, self.base_max, self.base_min = base_margin, base_max, base_min
        self.base: list[bool] = []   # off-grid flags of out-of-fight samples on the current step
        self.base_off = 0
        self.legit_rate = p0         # the test's current legit rate (p0 raised to the player's baseline)
        self.buf: list[float] = []
        self.step = NAN
        self.since = 0
        self.ring: list[tuple[bool, bool]] = []   # (toward, off) per yaw sample
        self.prev: dict | None = None
        self.samples = 0

    def _restart(self) -> None:
        self.cusum.s = 0.0
        self.ring.clear()
        self.base.clear()
        self.base_off = 0

    def baseline(self) -> float:
        """Off-grid share of the player's out-of-fight rotation, NaN before base_min samples."""
        return self.base_off / len(self.base) if len(self.base) >= self.base_min else NAN

    def _base_add(self, off: bool) -> None:
        self.base.append(off)
        self.base_off += off
        if len(self.base) > self.BASE_KEEP:
            self.base_off -= self.base.pop(0)
        b = self.baseline()
        if math.isnan(b) or b > self.base_max:
            return   # untested player (see _sample): keep the last rates
        q = min(0.49, max(self.p0, math.floor(b * self.base_margin * 1000.0 + 0.5) / 1000.0))
        if q != self.legit_rate:
            self.legit_rate = q
            p1 = max(self.p1, 2.0 * q)
            self.cusum.up = math.log(p1 / q)
            self.cusum.down = math.log((1.0 - p1) / (1.0 - q))

    def _sample(self, d: float, toward: bool | None, fight: bool) -> None:
        self.buf.append(d)
        if len(self.buf) > self.KEEP:
            del self.buf[0]
        self.samples += 1
        self.since += 1
        if math.isnan(self.step):
            if len(self.buf) >= self.LEARN and self.since >= self.CHECK:
                self.since = 0
                self.step, _ = learn_step(self.buf)
                self._restart()
            return
        if self.since >= self.CHECK:
            self.since = 0
            s2, _ = learn_step(self.buf[-self.CHECK:])
            if math.isnan(s2):
                self.step = NAN
                self._restart()
                return
            if abs(s2 - self.step) / self.step > self.STEP_CHANGE:
                self.step = s2
                self._restart()
                return
        if d > self.max_mult * self.step:
            return   # big flicks: a tiny step error and mouse acceleration both scale with the size of the move
        off = off_grid(d, self.step)
        if not fight:
            self._base_add(off)
            return
        b = self.baseline()
        if math.isnan(b) or b > self.base_max:
            self.cusum.s = 0.0   # no baseline yet, or a mouse too noisy to test
            return
        self.cusum.add(off)
        if toward is not None:
            self.ring.append((toward, off))
            if len(self.ring) > self.RING:
                del self.ring[0]

    def bias(self) -> float:
        t = [o for tw, o in self.ring if tw]
        a = [o for tw, o in self.ring if not tw]
        if len(t) < self.MIN_SIDE or len(a) < self.MIN_SIDE:
            return NAN
        return sum(t) / len(t) - sum(a) / len(a)

    def off_share(self) -> float:
        return sum(o for _, o in self.ring) / len(self.ring) if self.ring else NAN

    def feed(self, f: dict) -> bool:
        """f: frame with tick, yaw_exact (None = no rotation), pitch_exact, has_opp and err (aim error = yaw - the
        opponent's bearing, None without an opponent). Returns True when this frame raises an alert."""
        p, self.prev = self.prev, f
        err_yaw_prev = p.get("err") if p is not None else None
        if p is None or f.get("yaw_exact") is None or p.get("yaw_exact") is None or f["tick"] - p["tick"] != 1:
            return False
        fight = bool(f.get("has_opp"))
        dy = float(f["yaw_exact"]) - float(p["yaw_exact"])
        if MIN_DELTA < abs(dy) < MAX_DELTA:
            self._sample(abs(dy), None if err_yaw_prev is None else ((dy < 0) == (err_yaw_prev > 0)), fight)
        if abs(float(f["pitch_exact"])) < 89.0 and abs(float(p["pitch_exact"])) < 89.0:
            dp = abs(float(f["pitch_exact"]) - float(p["pitch_exact"]))
            if MIN_DELTA < dp < MAX_DELTA:
                self._sample(dp, None, fight)
        if not fight or math.isnan(self.step) or self.cusum.s < self.cusum.h:
            return False
        b = self.bias()
        if math.isnan(b) or b < self.min_bias:
            return False
        self.cusum.s = 0.0
        return True
