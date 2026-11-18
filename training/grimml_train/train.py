"""Train or test one module: load the labelled windows, hold out whole sessions, write a run folder."""
from __future__ import annotations

import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train import dataset as ds
from grimml_train.catalog import CHEAT, LEGIT, LabelRules, session_table
from grimml_train.evaluation import evaluate_module, transfer
from grimml_train.features.sets import feature_sets
from grimml_train.modules import ModuleRegistry, ModuleSpec
from grimml_train.paths import CACHE_DIR, DATA_DIR, RUNS_DIR
from grimml_train.report import save_model, write_run


@dataclass
class DataOptions:
    """Where the windows come from and which sessions count. Not module specific."""

    folder: Path = DATA_DIR
    workers: int = 0
    use_cache: bool = True
    include_interrupted_legit: bool = False
    legit_ids: set[str] = field(default_factory=set)
    drop_ids: set[str] = field(default_factory=set)
    hard_invalid: str = "auto"                     # auto | keep | drop
    validity_fixed_from_ms: int | None = None      # overrides labels.yaml
    bulk_dir: Path | None = None                   # sample extra legit windows from a bulk dump
    bulk_processed: Path | None = None             # or reuse already decoded bulk windows (parquet)
    bulk_holdout: Path = Path("E:/recordings_processed/splits/live_holdout_accounts.txt")
    bulk_files: int = 6000
    bulk_per_player: int = 30
    bulk_groups: int = 8


@dataclass
class Prepared:
    windows: pd.DataFrame      # usable, labelled windows with blocks
    sessions: pd.DataFrame
    header: dict
    notes: list[str]


def prepare(spec: ModuleSpec, opts: DataOptions, rules: LabelRules) -> Prepared:
    """Decode (cached), label and block every window of the data folder for this module's settings."""
    sessions = session_table(rules, min_secs=spec.min_secs, include_interrupted_legit=opts.include_interrupted_legit,
                             legit_ids=opts.legit_ids, drop_ids=opts.drop_ids)
    files = ds.find_files(opts.folder) if Path(opts.folder).exists() else []
    if not files:
        raise SystemExit(f"no .gmlml files in '{opts.folder}'")
    cache = CACHE_DIR if opts.use_cache else None
    raw, errs = ds.decode_folder(opts.folder, workers=opts.workers, paths=files, cache_dir=cache)
    notes: list[str] = []
    if errs:
        notes.append(f"{len(errs)} files could not be decoded (first: {Path(errs[0][0]).name}: {errs[0][1][:80]})")

    use_bulk = bool(opts.bulk_dir or opts.bulk_processed)
    if use_bulk:
        if opts.bulk_processed:
            braw, src = ds.load_processed_bulk(opts.bulk_processed, opts.bulk_holdout)
        else:
            paths = ds.sample_bulk(opts.bulk_dir, opts.bulk_files, spec.seed, CACHE_DIR / "bulk_listing.txt")
            braw, _ = ds.decode_folder(opts.bulk_dir, workers=opts.workers, paths=paths, cache_dir=None)
            src = f"{len(paths)} sampled files"
        braw["mark_id"] = ds.BULK_ID
        raw = pd.concat([raw, braw], ignore_index=True)
        sessions = pd.concat([sessions, pd.DataFrame([{
            "mark_id": ds.BULK_ID, "label": LEGIT, "client": "", "module": "LEGIT", "settings": "",
            "start_ms": np.nan, "end_ms": np.nan, "interrupted": False, "multi_module": False,
            "note": "sampled bulk legit", "secs": np.nan, "exclude_reason": "",
        }])], ignore_index=True)

    fixed_from = opts.validity_fixed_from_ms if opts.validity_fixed_from_ms is not None else rules.validity_fixed_from_ms
    w = ds.label_windows(raw, sessions, trim_s=spec.trim_s, roles=spec.roles, schema4_only=spec.schema4,
                         validity_fixed_from_ms=fixed_from, hard_invalid=opts.hard_invalid)
    if use_bulk:
        w = ds.mark_bulk(w, opts.bulk_per_player, spec.seed)
        nb = w[(w["mark_id"] == ds.BULK_ID) & w["use"]]
        notes.append(f"bulk legit: {len(nb)} windows from {nb['anon_player_id'].nunique()} players from {src} "
                     f"(cap {opts.bulk_per_player}/player). Bulk labels are not verified and come from other servers: "
                     "check the shortcut-only line.")
    notes.append(f"hard-validity filter applied to {int(w['validity_trusted'].sum())}/{len(w)} windows "
                 "(older recorder builds have sticky validity bits).")
    print("window usage:\n" + w["reason"].replace("", "used").value_counts().to_string())

    d, dropped_gap = ds.assign_blocks(w[w["use"]], rules, spec.chunk_min, spec.chunk_gap_s, opts.bulk_groups)
    d = d[d["label"].isin([LEGIT, CHEAT])].copy()
    if "window_key" not in d.columns:
        d["window_key"] = d["path"]
    header = {
        "folder": str(opts.folder), "windows_used": int(len(d)), "legit_windows": int((d["label"] == LEGIT).sum()),
        "cheat_windows": int((d["label"] == CHEAT).sum()), "chunk_gap_dropped": dropped_gap,
        "trim_s": spec.trim_s, "chunk_min": spec.chunk_min, "roles": list(spec.roles) or "all",
        "schema4": spec.schema4, "train_clients": list(spec.train_clients),
    }
    return Prepared(d, sessions, header, notes)


def _cheat_of(p: Prepared, spec: ModuleSpec, reg: ModuleRegistry) -> pd.DataFrame:
    d = p.windows
    return d[(d["label"] == CHEAT) & d["id_module"].fillna("").map(lambda m: reg.matches(spec, m))]


def _run_dir(spec: ModuleSpec, kind: str) -> Path:
    return RUNS_DIR / spec.slug / f"{time.strftime('%Y%m%d_%H%M%S')}{kind}"


def train_module(spec: ModuleSpec, opts: DataOptions, rules: LabelRules, reg: ModuleRegistry,
                 out: Path | None = None) -> Path:
    """Held-out evaluation + the final model for one module. Returns the run folder."""
    p = prepare(spec, opts, rules)
    legit = p.windows[p.windows["label"] == LEGIT]
    if legit.empty:
        raise SystemExit("no usable legit windows" + (" (schema4 needs schema 4 legit recordings)" if spec.schema4 else ""))
    cheat_all = _cheat_of(p, spec, reg)
    cl = cheat_all["id_client"].fillna("").str.lower()
    cheat, other = cheat_all[cl.isin(spec.train_clients)], cheat_all[~cl.isin(spec.train_clients)]
    if cheat.empty:
        raise SystemExit(f"no usable {spec.name} cheat windows from {', '.join(spec.train_clients)}: "
                         "check sessions.csv and `grimml-train sessions`")

    leg, stats = legit, None
    if spec.match_activity:
        cheat, leg, stats = ds.match_activity(cheat, legit, list(spec.match_cols), spec.seed)
        print(f"training {spec.name}: activity-matched {stats['cheat_before']}->{stats['cheat_after']} cheat, "
              f"{stats['legit_before']}->{stats['legit_after']} legit windows", flush=True)
    else:
        print(f"training {spec.name}: {len(cheat)} cheat windows", flush=True)
    sets = feature_sets(p.windows, schema4=spec.schema4, families=spec.families)
    res, clf = evaluate_module(spec.name, cheat, leg, p.sessions, sets, spec.seed)
    if stats:
        res["matched"] = stats
        p.notes.append("Activity matching is on, so the transfer line is skipped: use `grimml-train loco`.")
    elif "skipped" not in res and not other.empty:
        res["transfer"] = transfer(cheat, legit, other, sets["core"], spec.seed)

    out = out or _run_dir(spec, "")
    header = {"module": spec.name, "run_at": out.name, **p.header, "settings": asdict(spec)}
    if clf is not None:
        save_model(out / "model", spec.slug, clf, sets["core"], res, built=out.name)
    print("\n" + write_run(out, header, [res], p.notes))
    print(f"\nsaved to {out}")
    return out


def loco_module(spec: ModuleSpec, test_client: str, opts: DataOptions, rules: LabelRules, reg: ModuleRegistry,
                out: Path | None = None) -> Path:
    """Leave-one-client-out: train on the module's train clients, test on cheat sessions of an unseen client.
    Legit is held out chunk by chunk, so the legit used for thresholds was never trained on either."""
    test_client = test_client.lower()
    trains = [c for c in spec.train_clients if c != test_client]
    p = prepare(spec, opts, rules)
    legit = p.windows[p.windows["label"] == LEGIT]
    cheat = _cheat_of(p, spec, reg)
    cl = cheat["id_client"].fillna("").str.lower()
    tr, te = cheat[cl.isin(trains)], cheat[cl == test_client]
    r = {"module": spec.name, "test_client": test_client, "train_clients": trains,
         "train_windows": int(len(tr)), "train_segments": int(tr["block"].nunique()),
         "test_windows": int(len(te)), "test_segments": int(te["block"].nunique())}
    if tr.empty or te.empty:
        r["skipped"] = "no cheat windows for this module in the training clients or the test client"
    else:
        lg = legit
        if spec.match_activity:
            pool, lg, st = ds.match_activity(pd.concat([tr, te]), legit, list(spec.match_cols), spec.seed)
            tr, te = pool.loc[pool.index.isin(tr.index)], pool.loc[pool.index.isin(te.index)]
            r["matched"] = st
            r["train_windows"], r["test_windows"] = int(len(tr)), int(len(te))
        if tr.empty or te.empty or lg["block"].nunique() < 2:
            r["skipped"] = "too few windows left after activity matching"
        else:
            for name, cols in feature_sets(p.windows, schema4=spec.schema4, families=spec.families).items():
                r[name] = transfer(tr, lg, te, cols, spec.seed)
    p.notes.append("A held-out client is usually 1-3 sessions: treat the numbers as a hint and compare core with "
                   "shortcut-only.")
    out = out or _run_dir(spec, f"_loco_{test_client}")
    header = {"module": spec.name, "run_at": out.name, **p.header, "settings": asdict(spec)}
    print("\n" + write_run(out, header, [r], p.notes))
    print(f"\nsaved to {out}")
    return out
