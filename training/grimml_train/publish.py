"""Publish trained modules to the repo's models/ folder, which the detector plugin downloads.

Each run folder replaces only its own module in models/manifest.json; every other module stays as it is. The
manifest version goes up by one per publish, and servers switch to it without a restart.

alert_share (the share of a fight's windows that must be flagged) is set above every legit fight seen in held-out
testing: max(95th percentile, max of legit fight shares) + margin.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path

from grimml_train.modules import slugify
from grimml_train.paths import MODELS_DIR

DEFAULT_MARGIN = 0.04


def check_name(module: str) -> str:
    return "ML" + re.sub(r"[^A-Za-z0-9]", "", module.title())


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


@dataclass
class Candidate:
    entry: dict
    files: tuple[Path, Path]  # model .txt, meta .json


def candidate_from_run(run: Path, margin: float) -> Candidate:
    payload = json.loads((run / "result.json").read_text(encoding="utf-8"))
    res = next((r for r in payload["results"] if "test_client" not in r), None)
    if res is None or "skipped" in res:
        raise SystemExit(f"{run}: no trained model in this run")
    name = res["module"]
    txt, meta = run / "model" / f"{slugify(name)}.txt", run / "model" / f"{slugify(name)}.json"
    fights = res.get("fights") or {}
    if not txt.exists() or not meta.exists() or "legit_share_max" not in fights:
        raise SystemExit(f"{run}: model files or fight-level results missing")
    share = round(max(fights["legit_share_p95"], fights["legit_share_max"]) + margin, 2)
    entry = {
        "module": name, "check": check_name(name),
        "description": f"ML model for {name} (experimental)",
        "model": txt.name, "meta": meta.name,
        "enabled": False, "alert_share": share,
        "held_out_core_auc": round(res["core"]["auc"], 3),
        "cheat_sessions_caught": f"{fights.get('cheat_caught')}/{fights.get('cheat_units')}",
        "source_run": f"{run.parent.name}/{run.name}",
    }
    print(f"{check_name(name):<14} alert at {share:.0%} of 10+ windows  "
          f"(legit max {fights['legit_share_max']:.0%}, AUC {res['core']['auc']:.2f}, "
          f"caught {entry['cheat_sessions_caught']})")
    return Candidate(entry, (txt, meta))


def publish(runs: list[Path], *, enable: bool | None = None, margin: float = DEFAULT_MARGIN,
            dry_run: bool = False, models_dir: Path = MODELS_DIR) -> dict | None:
    """Replace each run's module in models/. enable: True/False switches the module on/off, None keeps its current
    state (new modules start switched off). Returns the new manifest (None on a dry run)."""
    cands = [candidate_from_run(Path(r), margin) for r in runs]
    names = [c.entry["module"].lower() for c in cands]
    if len(set(names)) != len(names):
        raise SystemExit("two runs for the same module")

    manifest_path = models_dir / "manifest.json"
    old = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"version": 0, "models": []}
    current = {e["module"].lower(): e for e in old["models"]}
    for c in cands:
        prev = current.get(c.entry["module"].lower())
        c.entry["enabled"] = enable if enable is not None else bool(prev and prev.get("enabled"))
        print(f"  {c.entry['check']}: {'ON' if c.entry['enabled'] else 'off'}"
              + ("" if prev else " (new module)"))
    if dry_run:
        return None

    models_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    replaced = {c.entry["module"].lower(): c for c in cands}
    for e in old["models"]:
        c = replaced.pop(e["module"].lower(), None)
        if c is None:
            entries.append(e)  # untouched module
            continue
        for f in (e["model"], e["meta"]):
            (models_dir / f).unlink(missing_ok=True)
        entries.append(_install(c, models_dir))
    entries += [_install(c, models_dir) for c in replaced.values()]  # new modules, at the end

    manifest = {
        "version": int(old["version"]) + 1,
        "published": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "source_run": ", ".join(c.entry["source_run"] for c in cands),
        "models": entries,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"published models v{manifest['version']} to {models_dir}: commit and push models/ to roll it out, "
          "then run `grimml-train parity`")
    return manifest


def _install(c: Candidate, models_dir: Path) -> dict:
    for p in c.files:
        shutil.copyfile(p, models_dir / p.name)
    e = dict(c.entry)
    e["model_sha256"] = sha256(models_dir / e["model"])
    e["meta_sha256"] = sha256(models_dir / e["meta"])
    return e
