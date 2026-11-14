"""Publish models from a prestige_quick run to ../models so the detector plugin picks them up.

    python publish_models.py out_quick/20261114_003501 --enable "Kill Aura,Silent Aim"

Copies <run>/models/*.txt|json into the repo's models/ folder, writes models/manifest.json with sha256 sums
and a version one higher than the last, then you commit and push. Servers running the detector download the
new version within models.check-every-minutes (or on /gmld update) and switch to it without a restart.

alert_share (the share of a fight's windows that must be flagged) is set above every legit fight seen in
held-out testing: max(95th pct, max of legit fight shares) + --margin. Modules not in --enable are published
switched off, so they load but never flag.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODELS = REPO / "models"


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def check_name(module: str) -> str:
    return "ML" + re.sub(r"[^A-Za-z0-9]", "", module.title())


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run", help="a prestige_quick output folder (has results.json and models/)")
    ap.add_argument("--enable", default="Kill Aura", help="comma list of modules that may flag")
    ap.add_argument("--margin", type=float, default=0.04)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    run = Path(args.run)
    if not run.is_absolute():
        run = Path(__file__).resolve().parent / run
    results = json.loads((run / "results.json").read_text(encoding="utf-8"))["results"]
    enable = {m.strip().lower() for m in args.enable.split(",") if m.strip()}

    old = MODELS / "manifest.json"
    version = (json.loads(old.read_text(encoding="utf-8"))["version"] if old.exists() else 0) + 1
    entries = []
    for r in results:
        name = r["module"]
        src_txt, src_json = run / "models" / f"{slug(name)}.txt", run / "models" / f"{slug(name)}.json"
        if not src_txt.exists() or not src_json.exists():
            print(f"skip {name}: no saved model in this run")
            continue
        fights = r.get("fights") or {}
        if "legit_share_max" not in fights:
            print(f"skip {name}: no fight-level results")
            continue
        share = round(max(fights["legit_share_p95"], fights["legit_share_max"]) + args.margin, 2)
        on = name.lower() in enable
        entries.append({
            "module": name, "check": check_name(name),
            "description": f"ML model for {name} (experimental)",
            "model": src_txt.name, "meta": src_json.name,
            "enabled": on, "alert_share": share,
            "held_out_core_auc": round(r["core"]["auc"], 3),
            "cheat_sessions_caught": f"{fights.get('cheat_caught')}/{fights.get('cheat_units')}",
            "_src": (src_txt, src_json),
        })
        print(f"{'ON ' if on else 'off'} {check_name(name):<14} alert at {share:.0%} of 10+ windows  "
              f"(legit max {fights['legit_share_max']:.0%}, AUC {r['core']['auc']:.2f})")
    if not entries:
        raise SystemExit("nothing to publish")
    if args.dry_run:
        return

    MODELS.mkdir(exist_ok=True)
    for old_file in MODELS.glob("*"):
        if old_file.suffix in (".txt", ".json") and old_file.name != "manifest.json":
            old_file.unlink()
    for e in entries:
        for p in e.pop("_src"):
            shutil.copyfile(p, MODELS / p.name)
        e["model_sha256"] = sha256(MODELS / e["model"])
        e["meta_sha256"] = sha256(MODELS / e["meta"])
    manifest = {
        "version": version,
        "published": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "source_run": run.name,
        "models": entries,
    }
    (MODELS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"published models v{version} to {MODELS} - commit and push models/ to roll it out")


if __name__ == "__main__":
    main()
