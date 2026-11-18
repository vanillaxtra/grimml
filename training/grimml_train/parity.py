"""Write the detector's parity fixtures: real windows with Python's features and model scores.

    grimml-train parity            (after publish)

The detector build (mvn test) recomputes features and scores in Java from the same frames and fails if they
differ. Only anonymised frame numbers are written: no names, ids or mark ids.
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

import lightgbm as lgb
import numpy as np

from grimml_train.decode.window import decode_file
from grimml_train.features.extract import features_from_decoded
from grimml_train.paths import DATA_DIR as FOLDER
from grimml_train.paths import MODELS_DIR as MODELS
from grimml_train.paths import PARITY_DIR as OUT

# a mix of cheat, friend duels and bot duels
MARKS = ["m1900082b", "m96dd5b71", "m16f82554", "m4bc0a6be", "mb1f0f01e", "m39bac321"]
PER_MARK = 12


def num(x) -> str:
    return "nan" if x is None or (isinstance(x, float) and np.isnan(x)) else repr(float(x))


def main() -> None:
    manifest = json.loads((MODELS / "manifest.json").read_text(encoding="utf-8"))
    boosters = {}
    for e in manifest["models"]:
        meta = json.loads((MODELS / e["meta"]).read_text(encoding="utf-8"))
        boosters[e["check"]] = (lgb.Booster(model_file=str(MODELS / e["model"])), meta["features"])
    # the core fixture uses the core-only models (schema 4 models are checked by export_schema4)
    from grimml_train.features.extract import FEATURE_COLUMNS
    names = next(cols for _, cols in boosters.values() if set(cols) <= set(FEATURE_COLUMNS))

    files = sorted(f.path for f in os.scandir(FOLDER) if any(f"_{m}_" in f.name for m in MARKS))
    rng = np.random.default_rng(7)
    by_mark: dict[str, list[str]] = {}
    for p in files:
        by_mark.setdefault(re.search(r"_(m[0-9a-f]{8})_", p).group(1), []).append(p)
    picked = [p for m in MARKS for p in rng.permutation(by_mark.get(m, []))[:PER_MARK]]

    OUT.mkdir(parents=True, exist_ok=True)
    lines = ["# features=" + ",".join(names)]
    for p in picked:
        d = decode_file(Path(p))
        fr = d.get("frames") or []
        if len(fr) < 2:
            continue
        feats = features_from_decoded(d)
        x = np.array([[feats[n] for n in names]], dtype=float)
        scores = ";".join(f"{c}={float(b.predict(x)[0])!r}" for c, (b, cols) in boosters.items() if cols == names)
        cols = [[f["yaw_centi"] for f in fr], [f["pitch_centi"] for f in fr], [f["x_milli"] for f in fr],
                [f["z_milli"] for f in fr], [f["flags"] for f in fr]]
        events = [e for e in (d.get("events") or []) if e.get("event_type") == "attack" and e.get("nanos") is not None]
        att = [str(int(e["nanos"])) for e in events]
        lines.append("W|" + ",".join(num(feats[n]) for n in names) + "|" + scores + "|"
                     + ";".join(",".join(str(int(v)) for v in c) for c in cols) + "|" + ",".join(att))
    (OUT / "windows.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(lines) - 1} windows to {OUT / 'windows.txt'}")
    export_schema4()
    export_live()
    export_lunge()


# schema 4 sessions for the schema 4 feature parity (add legit and other modules here as they are recorded)
MARKS4 = ["m5123c635", "m8f52a835", "m1eaa1d2d", "m1c38d70b", "ma90a44db", "m4ca53577", "m1ca0279f", "m4f107966"]
PER_MARK4 = 8
KEEP_EVENTS = {"attack": 2, "swing": 7, "hotbar_swap": 9, "block_place": 11, "knockback": 12, "rtt": 21,
               "break": 22, "input": 27, "move_state": 28, "entity_sent": 29, "rtt_sent": 30, "entity_spawn": 31}
# spear-mace sessions for the swap_/lunge_/mace_/stun_ parity (features/spearmace.py). The schema 3 ones are old
# afk-alt / friend-duel sessions that are never trained on; they are here only because they are the spear-mace data
# that exists so far. Schema 3 windows are written as W3 lines (the detector runs compute(w, false) on them).
MARKS_SM = ["m3ddf06f9", "mb6a1b94b", "m5045e1ea", "m004031bb", "m89ff4396", "m69a17c52", "ma90a44db"]
PER_MARK_SM = 4


def _event_c(e: dict) -> int:
    """Event c with spear categories resolved from item ids (the detector gets them resolved live)."""
    from grimml_train.features.spearmace import resolve_cat

    c = e["c"]
    if e["event_type"] == "hotbar_swap" and e["a"] != 40:
        return resolve_cat(c & 0xFF, e.get("d")) | (resolve_cat((c >> 8) & 0xFF, e.get("e")) << 8)
    if e["event_type"] == "attack":
        return (c & 0xFF) | (resolve_cat((c >> 8) & 0xFF, e.get("e")) << 8)
    return c


def _spear_mace_coverage(d: dict) -> int:
    """How many spear-mace features a window sets (0 = none of the family's data in it)."""
    from grimml_train.features.schema4 import schema4_features
    from grimml_train.features.spearmace import SPEAR_MACE_FEATURES

    if d["window"]["schema_version"] < 3:
        return 0
    f = schema4_features(d)
    counts = ("lunge_stabs", "mace_hits", "stun_slams", "swap_order_violations")
    return sum(1 for n in SPEAR_MACE_FEATURES if n not in counts and f[n] == f[n])


def _synthetic_spear_mace() -> list[dict]:
    """Built schema 4 windows (not recordings) so the client-tick, opponent and rotation parts of the spear-mace family
    are parity-checked before real schema 4 spear-mace sessions exist: a macro lunge, a stun slam on a blocking
    opponent, a silent-aim mace drop with swap back. Replace with recorded windows once those exist (MARKS_SM)."""
    ms = 1_000_000

    def frame(t, y=0, ground=True, yaw=0.0, opp_state=1):
        return {"tick": t, "flags": 1 if ground else 0, "on_ground": ground, "client_tick": t + 7, "yaw_exact": yaw,
                "pitch_exact": 0.0, "sneak": False, "opp_anon": 9, "opp_dx_milli": 300, "opp_dy_milli": 0,
                "opp_dz_milli": 2800, "opp_state": opp_state, "opp_block_ago_ms": 400, "opp_hurt_ago_ms": 600,
                "max_gap_ms": 50, "burst": 0, "yaw_centi": int(round(yaw * 100)), "pitch_centi": 0,
                "x_milli": 0, "z_milli": 0, "y_milli": y}

    def ev(kind, t_ms, a=0, b=0, c=0, d=0, g=0, ct_shift=0):
        tick = int(t_ms // 50)
        return {"event_type": kind, "tick": tick, "nanos": int(t_ms * ms), "a": a, "b": b, "c": c, "d": d, "e": 0,
                "f": 0, "g": g, "client_tick": tick + 7 + ct_shift}

    def sw(t, frm, to, shift=0):
        return ev("hotbar_swap", t, a=1, b=2, c=frm | (to << 8), ct_shift=shift)

    def hit(t, cat, shift=0):
        return ev("attack", t, a=77, b=5, c=cat << 8, g=1 | (12 << 8), ct_shift=shift)

    out = []
    lunge = []
    for i in range(14):
        t = 100 + i * 230 + (i % 3) * 20
        lunge += [sw(t, 1, 4), ev("break", t + 0.4, a=8), ev("swing", t + 0.5),
                  sw(t + 50 + (i % 2) * 50, 4, 1, shift=0 if i == 5 else 1)]
    out.append({"frames": [frame(t) for t in range(160)], "events": lunge})
    stun = []
    for i in range(5):
        t = 200 + i * 1400
        stun += [sw(t, 1, 2), hit(t + 1, 2), sw(t + 33 + i, 2, 3, shift=1), hit(t + 34 + i, 3, shift=1),
                 sw(t + 90, 3, 1, shift=2)]
    out.append({"frames": [frame(t, opp_state=8 if t % 28 < 14 else 1) for t in range(160)], "events": stun})
    frames, mace = [], []
    for t in range(160):
        ph = t % 40
        air = 5 <= ph < 35
        y = int(9000 * (1 - ((ph - 20) / 15) ** 2)) if air else 0
        frames.append(frame(t, y=max(y, 0), ground=not air, yaw=30.0 if ph == 33 else 2.0 * (t % 3)))
    for c in range(4):
        t = (c * 40 + 33) * 50
        mace += [sw(t - 15, 1, 3), hit(t + 2, 3), sw(t + 60 + 10 * c, 3, 1, shift=1)]
    out.append({"frames": frames, "events": mace})
    for w in out:
        w["window"] = {"schema_version": 4}
        w["events"].sort(key=lambda e: e["nanos"])
    return out


def export_schema4() -> None:
    from grimml_train.features.extract import FEATURE_COLUMNS
    from grimml_train.features.schema4 import SCHEMA4_FEATURES, schema4_features

    # every published model whose features this window type can supply (core + schema 4) is scored too
    manifest = json.loads((MODELS / "manifest.json").read_text(encoding="utf-8"))
    known = set(FEATURE_COLUMNS) | set(SCHEMA4_FEATURES)
    boosters = []
    for e in manifest["models"]:
        cols = json.loads((MODELS / e["meta"]).read_text(encoding="utf-8"))["features"]
        if set(cols) <= known:
            boosters.append((e["check"], lgb.Booster(model_file=str(MODELS / e["model"])), cols))

    files = sorted(f.path for f in os.scandir(FOLDER) if any(f"_{m}_" in f.name for m in MARKS4))
    picked = files[:: max(1, len(files) // (PER_MARK4 * len(MARKS4)))][: PER_MARK4 * len(MARKS4)]
    for m in MARKS_SM:
        # the windows of each session that set the most spear-mace features
        cov = [(_spear_mace_coverage(decode_file(Path(p))), p)
               for p in sorted(f.path for f in os.scandir(FOLDER) if f"_{m}_" in f.name) if p not in picked]
        picked += [p for c, p in sorted(cov, key=lambda x: -x[0])[:PER_MARK_SM] if c > 0]
    lines = ["# features=" + ",".join(SCHEMA4_FEATURES)]
    windows = [(decode_file(Path(p)), True) for p in picked] + [(d, False) for d in _synthetic_spear_mace()]
    for d, recorded in windows:
        schema = d["window"]["schema_version"]
        if schema < 3:
            continue
        feats = schema4_features(d)
        scores = ""
        if schema >= 4 and recorded:
            allf = {**features_from_decoded(d), **feats}
            scores = ";".join(f"{c}={float(b.predict(np.array([[allf[n] for n in cols]], dtype=float))[0])!r}"
                              for c, b, cols in boosters)
        fr = []
        for f in d["frames"]:
            opp = f.get("opp_anon") is not None
            rot = f.get("yaw_exact") is not None
            fr.append(",".join(str(v) for v in (
                f["tick"], f["flags"], f.get("client_tick") or 0, int(rot),
                repr(f["yaw_exact"]) if rot else "0", repr(f["pitch_exact"]) if rot else "0", int(opp),
                f["opp_dx_milli"] if opp else 0, f["opp_dy_milli"] if opp else 0, f["opp_dz_milli"] if opp else 0,
                f["opp_state"] if opp else 0, f["opp_block_ago_ms"] if opp else -1, f["opp_hurt_ago_ms"] if opp else -1,
                f.get("max_gap_ms") or 0, f.get("burst") or 0, f["yaw_centi"], f["pitch_centi"], f["x_milli"],
                f["z_milli"], f["y_milli"])))
        ev = []
        for e in d["events"]:
            t = KEEP_EVENTS.get(e["event_type"])
            if t is not None:
                ev.append(",".join(str(v) for v in (t, e["tick"], e["nanos"], e["a"], e["b"], _event_c(e), e["d"],
                                                     e.get("g") or 0, e.get("client_tick") or 0)))
        tag = "W4|" if schema >= 4 else "W3|"
        lines.append(tag + ",".join(num(feats[n]) for n in SCHEMA4_FEATURES) + "|" + ";".join(fr) + "|" + ";".join(ev)
                     + "|" + scores)
    (OUT / "windows4.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(lines) - 1} schema 4 windows to {OUT / 'windows4.txt'}")



# live AimGrid check: continuous frame streams (deduplicated across overlapping windows) replayed through Python
# LiveGrid; the detector replays the same frames through AimGrid.Live and must alert on the same ticks.
LIVE_MARKS = ["m8f52a835", "m1c38d70b"]
LIVE_TICKS = 4000


def export_live() -> None:
    from grimml_train.features.aimgrid import LiveGrid
    from grimml_train.features.schema4 import _aim_track

    lines = ["# live AimGrid streams: F|tick,rot,yaw,pitch,opp,aim,err ... then A|alert ticks and S|final step"]
    for m in LIVE_MARKS:
        ticks: dict[int, tuple] = {}
        for p in sorted(f.path for f in os.scandir(FOLDER) if f"_{m}_" in f.name):
            d = decode_file(Path(p))
            fr = d["frames"]
            for f, a in zip(fr, _aim_track(fr)):
                rot = f.get("yaw_exact") is not None
                ticks[f["tick"]] = (f["tick"], int(rot), f["yaw_exact"] if rot else 0.0, f["pitch_exact"] if rot else 0.0,
                                    int(f.get("opp_anon") is not None), int(a is not None), a["err_yaw"] if a else 0.0)
        seq = [ticks[k] for k in sorted(ticks)][:LIVE_TICKS]
        g = LiveGrid()
        alerts = []
        for t, rot, y, pt, opp, aim, err in seq:
            frame = {"tick": t, "yaw_exact": y if rot else None, "pitch_exact": pt, "has_opp": bool(opp),
                     "err": err if aim else None}
            if g.feed(frame):
                alerts.append(t)
        lines.append("F|" + ";".join(f"{t},{rot},{y!r},{pt!r},{opp},{aim},{err!r}" for t, rot, y, pt, opp, aim, err in seq))
        lines.append("A|" + ",".join(map(str, alerts)))
        lines.append("S|" + num(g.step))
        print(f"live stream: {len(seq)} frames, {len(alerts)} alerts, step {g.step}")
    (OUT / "live_grid.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


# live lunge checks (LungeRate, LungeHold, SwapOrder): swap / attack / STAB streams (deduplicated across overlapping
# windows) replayed through Python LiveLunge; the detector replays the same events through LungeRate and must return
# the same alert bits on the same events.
LUNGE_MARKS = ["m040a616d", "m54e35fb3", "ma8352fe2", "mee87b920", "m69a17c52", "m6013e9ff", "m2538fffc", "ma90a44db",
               "m2ebed745", "m88e02374", "me716b56d"]  # last three: Auto Shield Breaker, fast hand stuns (AxeHold)
LUNGE_ACTIONS = 3000


def export_lunge() -> None:
    from grimml_train.features.spearmace import HIT, STAB_ACT, SWAP, LiveLunge, actions

    lines = ["# live lunge streams: E|kind,nanos,toCat,fromCat,clientTick (1 swap, 2 hit, 3 stab) ... "
             "then A|nanos:bits per alert, N|swap-lunges,same-tick swaps"]
    for m in LUNGE_MARKS:
        seen: dict[tuple, dict] = {}
        for p in sorted(f.path for f in os.scandir(FOLDER) if f"_{m}_" in f.name):
            for a in actions(decode_file(Path(p))["events"]):
                if a["k"] in (SWAP, HIT, STAB_ACT):
                    seen[(a["n"], a["k"], a.get("to"))] = a
        seq = [seen[k] for k in sorted(seen, key=lambda k: k[0])][:LUNGE_ACTIONS]
        if not seq:
            continue
        g = LiveLunge()
        alerts = []
        for a in seq:
            bits = g.feed(a)
            if bits:
                alerts.append(f"{a['n']}:{bits}")
        lines.append("E|" + ";".join(f"{a['k']},{a['n']},{a.get('to', 0)},{a.get('from', 0)},{a.get('ct') or 0}"
                                     for a in seq))
        lines.append("A|" + ",".join(alerts))
        lines.append(f"N|{g.lunges},{g.order_total}")
        print(f"lunge stream {m}: {len(seq)} events, {g.lunges} swap-lunges, {len(alerts)} alerts")
    (OUT / "live_lunge.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
