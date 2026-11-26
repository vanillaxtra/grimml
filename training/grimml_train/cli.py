"""grimml-train: train, test and publish one GrimML module at a time.

    grimml-train modules                         configured modules and their settings
    grimml-train sessions [--module "Kill Aura"] labelled sessions, file counts and whether each is used
    grimml-train train "Silent Aim"              held-out test + model -> runs/silent_aim/<time>/
    grimml-train loco "Kill Aura" --test-client doomsday
    grimml-train publish runs/silent_aim/<time> [--enable]
    grimml-train parity                          refresh the detector's Java/Python parity fixtures
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import asdict
from pathlib import Path

from grimml_train.paths import DATA_DIR


def _ids(s: str) -> set[str]:
    return {x.strip().lower() for x in s.split(",") if x.strip()}


def _add_data_options(p: argparse.ArgumentParser) -> None:
    g = p.add_argument_group("data")
    g.add_argument("--folder", type=Path, default=DATA_DIR, help="folder with the labelled .gmlml files")
    g.add_argument("--workers", type=int, default=0, help="decode processes (0 = auto)")
    g.add_argument("--no-cache", action="store_true", help="decode every file again instead of using .cache/")
    g.add_argument("--legit-ids", default="", help="comma list: only these legit sessions")
    g.add_argument("--drop-ids", default="", help="comma list: leave these sessions out")
    g.add_argument("--include-interrupted-legit", action="store_true")
    g.add_argument("--keep-hard-invalid", dest="hard_invalid", action="store_const", const="keep",
                   help="never apply the hard-validity filter")
    g.add_argument("--drop-hard-invalid", dest="hard_invalid", action="store_const", const="drop",
                   help="always apply the hard-validity filter, even to old recorder files")
    g.add_argument("--validity-fixed-from", default="", help="overrides validity_fixed_from in labels.yaml")
    g.add_argument("--bulk-dir", type=Path, help="also sample legit windows from this bulk dump")
    g.add_argument("--bulk-processed", type=Path, help="reuse already decoded bulk windows (parquet folder)")
    g.add_argument("--bulk-files", type=int, default=6000)
    g.add_argument("--bulk-per-player", type=int, default=30)
    p.set_defaults(hard_invalid="auto")


def _add_module_overrides(p: argparse.ArgumentParser) -> None:
    g = p.add_argument_group("module settings (override config/modules.yaml for this run)")
    g.add_argument("--schema4", dest="schema4", action="store_true", default=None)
    g.add_argument("--no-schema4", dest="schema4", action="store_false")
    g.add_argument("--match-activity", dest="match_activity", action="store_true", default=None)
    g.add_argument("--no-match-activity", dest="match_activity", action="store_false")
    g.add_argument("--train-clients", help="comma list")
    g.add_argument("--families", help="comma list of schema 4 feature prefixes, e.g. aim_,grid_")
    g.add_argument("--chunk-min", type=float)
    g.add_argument("--chunk-gap-s", type=float)
    g.add_argument("--trim-s", type=float)
    g.add_argument("--min-secs", type=float)
    g.add_argument("--roles", help="comma list, e.g. 1,2")
    g.add_argument("--seed", type=int)


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="grimml-train", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("modules", help="list configured modules and their settings")

    p = sub.add_parser("sessions", help="list labelled sessions and whether each is used")
    p.add_argument("--module", help="only this module's cheat sessions (and the legit ones)")
    p.add_argument("--folder", type=Path, default=DATA_DIR)

    p = sub.add_parser("train", help="train and test one module")
    p.add_argument("module")
    p.add_argument("--out", type=Path, help="run folder (default runs/<module>/<time>)")
    _add_module_overrides(p)
    _add_data_options(p)

    p = sub.add_parser("loco", help="leave-one-client-out test for one module")
    p.add_argument("module")
    p.add_argument("--test-client", required=True)
    p.add_argument("--out", type=Path)
    _add_module_overrides(p)
    _add_data_options(p)

    p = sub.add_parser("publish", help="put trained modules into models/ (only those modules change)")
    p.add_argument("runs", nargs="+", type=Path, help="run folders from `train`")
    on = p.add_mutually_exclusive_group()
    on.add_argument("--enable", dest="enable", action="store_true", default=None, help="let the module flag")
    on.add_argument("--disable", dest="enable", action="store_false", help="load the module but never flag")
    p.add_argument("--margin", type=float, default=0.04, help="added to the highest legit fight share")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--max-flag-share", type=float, default=None,
                   help="refuse a model that flags more than this share of a real server's players (default 0.08)")
    p.add_argument("--allow-flags", action="store_true", help="publish even if the real-server check fails")

    p = sub.add_parser("telemetry", help="real-server telemetry: data summary and how many players each live model flags")
    p.add_argument("--folder", type=Path, default=None, help="default training/telemetry/telemetry-data")

    sub.add_parser("parity", help="write the detector's parity fixtures from models/")
    return ap


def _spec(args):
    from grimml_train.modules import ModuleRegistry

    reg = ModuleRegistry.load()
    spec = reg.get(args.module).with_overrides(
        schema4=args.schema4, match_activity=args.match_activity, train_clients=args.train_clients, families=args.families,
        chunk_min=args.chunk_min, chunk_gap_s=args.chunk_gap_s, trim_s=args.trim_s, min_secs=args.min_secs,
        roles=args.roles, seed=args.seed)
    return reg, spec


def _data_options(args):
    from grimml_train.catalog import parse_time_ms
    from grimml_train.train import DataOptions

    return DataOptions(
        folder=args.folder, workers=args.workers, use_cache=not args.no_cache,
        include_interrupted_legit=args.include_interrupted_legit,
        legit_ids=_ids(args.legit_ids), drop_ids=_ids(args.drop_ids), hard_invalid=args.hard_invalid,
        validity_fixed_from_ms=parse_time_ms(args.validity_fixed_from),
        bulk_dir=args.bulk_dir, bulk_processed=args.bulk_processed,
        bulk_files=args.bulk_files, bulk_per_player=args.bulk_per_player)


def cmd_modules(_args) -> int:
    from grimml_train.catalog import CHEAT, read_sessions
    from grimml_train.modules import ModuleRegistry

    reg = ModuleRegistry.load()
    s = read_sessions()
    cheat = s[s["label"] == CHEAT]
    print(f"{'module':<22}{'sessions':>9}  {'features':<9}{'clients':<26}families")
    for name in reg.names():
        spec = reg.get(name)
        n = int(cheat["module"].map(lambda m: reg.matches(spec, m)).sum())
        print(f"{name:<22}{n:>9}  {'schema4' if spec.schema4 else 'core':<9}{','.join(spec.train_clients):<26}"
              f"{' '.join(spec.families)}")
    specs = [reg.get(n) for n in reg.names()]
    trainable = cheat[~cheat["multi_module"] & (cheat["module"] != "UNLOGGED")]  # these are never trained anyway
    unknown = sorted({m for m in trainable["module"] if not any(reg.matches(sp, m) for sp in specs)})
    if unknown:
        print("\nmodules in sessions.csv without a modules.yaml entry: " + ", ".join(unknown))
    return 0


def cmd_sessions(args) -> int:
    from grimml_train.catalog import CHEAT, LabelRules, session_table
    from grimml_train.dataset import MARK_RE, find_files
    from grimml_train.modules import ModuleRegistry

    reg = ModuleRegistry.load()
    spec = reg.get(args.module) if args.module else None
    t = session_table(LabelRules.load(), min_secs=spec.min_secs if spec else 60.0)
    if spec:
        t = t[(t["label"] != CHEAT) | t["module"].map(lambda m: reg.matches(spec, m))]
    files = find_files(args.folder) if Path(args.folder).exists() else []
    counts: dict[str, int] = {}
    for p in files:
        m = MARK_RE.search(p.name)
        if m:
            counts[m.group(1).lower()] = counts.get(m.group(1).lower(), 0) + 1
    print(f"folder: {args.folder}  ({len(files)} .gmlml files)")
    print(f"{'mark id':<12}{'label':<17}{'client':<10}{'module':<24}{'secs':>6}{'files':>7}  used?")
    for r in t.itertuples():
        print(f"{r.mark_id:<12}{r.label:<17}{r.client:<10}{r.module[:23]:<24}{r.secs:>6.0f}"
              f"{counts.get(r.mark_id, 0):>7}  {r.exclude_reason or 'yes'}")
    unknown = sorted(set(counts) - set(t["mark_id"])) if not spec else []
    if unknown:
        print("mark ids in the folder that are not in sessions.csv: " + ", ".join(unknown))
    return 0


def cmd_train(args) -> int:
    from grimml_train.catalog import LabelRules
    from grimml_train.train import train_module

    reg, spec = _spec(args)
    print(f"{spec.name}: " + ", ".join(f"{k}={v}" for k, v in asdict(spec).items() if k != "name"))
    train_module(spec, _data_options(args), LabelRules.load(), reg, out=args.out)
    return 0


def cmd_loco(args) -> int:
    from grimml_train.catalog import LabelRules
    from grimml_train.train import loco_module

    reg, spec = _spec(args)
    loco_module(spec, args.test_client, _data_options(args), LabelRules.load(), reg, out=args.out)
    return 0


def cmd_publish(args) -> int:
    from grimml_train.publish import publish

    publish(args.runs, enable=args.enable, margin=args.margin, dry_run=args.dry_run,
            max_flag_share=args.max_flag_share, allow_flags=args.allow_flags)
    return 0


def cmd_telemetry(args) -> int:
    import json

    from grimml_train import telemetry
    from grimml_train.paths import MODELS_DIR

    folder = args.folder or telemetry.TELEMETRY_DIR
    tel = telemetry.load(folder)
    print(f"{folder}: {telemetry.summary(tel)}")
    if tel.empty:
        return 0
    manifest = json.loads((MODELS_DIR / "manifest.json").read_text(encoding="utf-8"))
    print(f"published models v{manifest['version']}, line = each model's alert_share:")
    for e in manifest["models"]:
        meta = json.loads((MODELS_DIR / e["meta"]).read_text(encoding="utf-8"))
        r = telemetry.check_model(MODELS_DIR / e["model"], meta, e["alert_share"], tel)
        print(f"== {e['check']} ({'on' if e.get('enabled') else 'off'}) ==")
        print("\n".join(telemetry.lines({"gate": r})))
    return 0


def cmd_parity(_args) -> int:
    from grimml_train import parity

    parity.main()
    return 0


COMMANDS = {"modules": cmd_modules, "sessions": cmd_sessions, "train": cmd_train, "loco": cmd_loco,
            "publish": cmd_publish, "telemetry": cmd_telemetry,
            "parity": cmd_parity}


def main(argv: list[str] | None = None) -> int:
    from grimml_train.modules import ConfigError

    args = build_parser().parse_args(argv)
    try:
        return COMMANDS[args.cmd](args)
    except ConfigError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
