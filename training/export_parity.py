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
    names = boosters[next(iter(boosters))][1]

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


if __name__ == "__main__":
    main()
