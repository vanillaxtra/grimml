"""Write the detector's parity fixture: real windows with Python's features and model scores.

    python export_parity.py            (after publish_models.py)

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

HERE = Path(__file__).resolve().parent
MODELS = HERE.parent / "models"
OUT = HERE.parent / "detector" / "src" / "test" / "resources" / "parity"
FOLDER = HERE / "prestige temp test"
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


# schema 4 sessions for the schema 4 feature parity (add legit and other modules here as they are recorded)
MARKS4 = ["m5123c635", "m8f52a835", "m1eaa1d2d", "m1c38d70b", "ma90a44db", "m4ca53577", "m1ca0279f", "m4f107966"]
PER_MARK4 = 8
KEEP_EVENTS = {"attack": 2, "swing": 7, "hotbar_swap": 9, "block_place": 11, "knockback": 12, "rtt": 21,
               "input": 27, "move_state": 28, "entity_sent": 29, "rtt_sent": 30, "entity_spawn": 31}


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
    lines = ["# features=" + ",".join(SCHEMA4_FEATURES)]
    for p in picked:
        d = decode_file(Path(p))
        if d["window"]["schema_version"] < 4:
            continue
        feats = schema4_features(d)
        allf = {**features_from_decoded(d), **feats}
        scores = ";".join(f"{c}={float(b.predict(np.array([[allf[n] for n in cols]], dtype=float))[0])!r}"
                          for c, b, cols in boosters)
        fr = []
        for f in d["frames"]:
            opp = f.get("opp_anon") is not None
            rot = f.get("yaw_exact") is not None
            fr.append(",".join(str(v) for v in (
                f["tick"], f["flags"], f["client_tick"], int(rot),
                repr(f["yaw_exact"]) if rot else "0", repr(f["pitch_exact"]) if rot else "0", int(opp),
                f["opp_dx_milli"] if opp else 0, f["opp_dy_milli"] if opp else 0, f["opp_dz_milli"] if opp else 0,
                f["opp_state"] if opp else 0, f["opp_block_ago_ms"] if opp else -1, f["opp_hurt_ago_ms"] if opp else -1,
                f["max_gap_ms"], f["burst"], f["yaw_centi"], f["pitch_centi"], f["x_milli"], f["z_milli"])))
        ev = []
        for e in d["events"]:
            t = KEEP_EVENTS.get(e["event_type"])
            if t is not None:
                ev.append(",".join(str(v) for v in (t, e["tick"], e["nanos"], e["a"], e["b"], e["c"], e["d"],
                                                     e.get("g", 0), e.get("client_tick") or 0)))
        lines.append("W4|" + ",".join(num(feats[n]) for n in SCHEMA4_FEATURES) + "|" + ";".join(fr) + "|" + ";".join(ev)
                     + "|" + scores)
    (OUT / "windows4.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(lines) - 1} schema 4 windows to {OUT / 'windows4.txt'}")


if __name__ == "__main__":
    main()
