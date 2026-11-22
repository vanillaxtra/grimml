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


def _stream(step, n, rng, assist, opp=True, accel=0.0, start=0):
    """Frames turning toward/away from an opponent; the assist only nudges toward it. accel: share of deltas a
    non-linear mouse (acceleration, custom curve) scales off the grid, in both directions."""
    yaw, frames = 100.0, []
    for t in range(n):
        err = rng.uniform(-20, 20)
        d = step * rng.choice([-1, 1]) * max(1, int(rng.expovariate(1 / 10)))
        if accel and rng.random() < accel:
            d *= rng.uniform(1.03, 1.6)
        if assist and opp and rng.random() < 0.3:
            d += -math.copysign(rng.uniform(0.002, 0.05), err)
        yaw += d
        frames.append({"tick": start + t, "yaw_exact": yaw, "pitch_exact": 0.0, "has_opp": opp,
                       "err": err if opp else None})
    return frames


def _play(g, step, rng, assist, accel=0.0, calm=800, fight=4000):
    """Out-of-fight rotation first (the baseline), then a fight. Returns the alert count in the fight."""
    for f in _stream(step, calm, rng, assist, opp=False, accel=accel):
        g.feed(f)
    return sum(g.feed(f) for f in _stream(step, fight, rng, assist, accel=accel, start=calm))


def test_live_alerts_on_assist_and_not_on_legit():
    rng = random.Random(4)
    st = step_for(0.204)
    assert _play(LiveGrid(), st, rng, assist=False) == 0
    assert _play(LiveGrid(), st, rng, assist=True) > 0


def test_no_test_before_a_baseline():
    rng = random.Random(6)
    st = step_for(0.204)
    g = LiveGrid()
    assert not any(g.feed(f) for f in _stream(st, 4000, rng, assist=True))
    assert math.isnan(g.baseline())


def test_accelerated_mouse_raises_the_legit_rate_and_does_not_alert():
    rng = random.Random(7)
    st = step_for(0.204)
    for accel in (0.03, 0.05, 0.08, 0.2):
        g = LiveGrid()
        assert _play(g, st, rng, assist=False, accel=accel) == 0, accel
    g = LiveGrid()
    _play(g, st, rng, assist=False, accel=0.04)
    assert g.legit_rate > 0.03


def test_old_fixed_rate_would_flag_an_accelerated_mouse():
    """Guards the reason for the baseline: without it (legit rate fixed at 1%) a 5% curve alerts."""
    rng = random.Random(8)
    st = step_for(0.204)
    g = LiveGrid(base_margin=0.0, base_max=1.0)
    assert _play(g, st, rng, assist=False, accel=0.05) > 0


def test_assist_still_alerts_on_a_mildly_accelerated_mouse():
    rng = random.Random(9)
    st = step_for(0.204)
    assert _play(LiveGrid(), st, rng, assist=True, accel=0.03) > 0


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


def test_big_flicks_with_a_slightly_wrong_step_do_not_alert():
    """A 0.3% step error is invisible on small moves but puts 200-step flicks off the grid: they must be ignored."""
    rng = random.Random(10)
    st = step_for(0.204)
    g = LiveGrid()
    calm = _stream(st, 800, rng, assist=False, opp=False)
    for f in calm:
        g.feed(f)
    yaw, alerts = 100.0, 0
    for t in range(4000):
        err = rng.uniform(-20, 20)
        n = rng.choice([1, 2, 3, 5]) if rng.random() < 0.7 else rng.randint(150, 400)
        yaw += -math.copysign(n * st * (1.003 if n > 20 else 1.0), err)   # big flicks toward the opponent
        alerts += g.feed({"tick": 800 + t, "yaw_exact": yaw, "pitch_exact": 0.0, "has_opp": True, "err": err})
    assert alerts == 0
