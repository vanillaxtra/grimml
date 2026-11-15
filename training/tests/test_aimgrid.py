import math
import random

from grimml_train.features.aimgrid import LiveGrid, learn_step, off_grid, step_for


def _mouse(step, n, rng, extra=0.0):
    """Mouse-like deltas: mostly small whole multiples, plus an assist share of off-grid nudges."""
    out = []
    for _ in range(n):
        if rng.random() < extra:
            out.append(rng.uniform(0.001, 1.5))
        else:
            out.append(step * max(1, int(rng.expovariate(1 / 12))))
    return out


def test_learns_the_exact_step_and_not_a_fraction():
    rng = random.Random(1)
    for sens in (0.05, 0.204, 0.5, 0.9):
        st = step_for(sens)
        s, fit = learn_step(_mouse(st, 300, rng))
        assert abs(s - st) / st < 1e-6, (sens, s, st)
        assert fit > 0.99


def test_still_learns_with_an_assist_mixed_in():
    rng = random.Random(2)
    st = step_for(0.204)
    s, fit = learn_step(_mouse(st, 300, rng, extra=0.25))
    assert abs(s - st) / st < 1e-4
    assert 0.6 < fit < 0.9


def test_abstains_on_rotation_that_fits_no_grid():
    rng = random.Random(3)
    s, fit = learn_step([rng.uniform(0.001, 3.0) for _ in range(300)])
    assert math.isnan(s)


def test_off_grid():
    st = step_for(0.204)
    assert not off_grid(7 * st, st)
    assert not off_grid(-7 * st, st)
    assert off_grid(7.5 * st, st)
    assert off_grid(0.3 * st, st)


def _stream(step, n, rng, assist, opp=True):
    """Frames turning toward/away from an opponent; the assist only nudges toward it."""
    yaw, frames = 100.0, []
    for t in range(n):
        err = rng.uniform(-20, 20)
        d = step * rng.choice([-1, 1]) * max(1, int(rng.expovariate(1 / 10)))
        if assist and rng.random() < 0.3:
            d += -math.copysign(rng.uniform(0.002, 0.05), err)
        yaw += d
        frames.append({"tick": t, "yaw_exact": yaw, "pitch_exact": 0.0, "has_opp": opp, "err": err if opp else None})
    return frames


def test_live_alerts_on_assist_and_not_on_legit():
    rng = random.Random(4)
    st = step_for(0.204)
    legit = LiveGrid()
    assert not any(legit.feed(f) for f in _stream(st, 4000, rng, assist=False))
    cheat = LiveGrid()
    assert sum(cheat.feed(f) for f in _stream(st, 4000, rng, assist=True)) > 0


def test_live_learns_outside_fights_and_alerts_quickly_in_one():
    rng = random.Random(5)
    st = step_for(0.204)
    g = LiveGrid()
    for f in _stream(st, 600, rng, assist=False, opp=False):
        g.feed(f)
    assert abs(g.step - st) / st < 1e-6
    fight = _stream(st, 400, rng, assist=True)
    for f in fight:
        f["tick"] += 600
    first = next((i for i, f in enumerate(fight) if g.feed(f)), None)
    assert first is not None and first < 200   # under 10 s of fighting
