from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.config import load_config
from grimml_train.eval.report import confound_probe, evaluate
from grimml_train.export.onnx_export import export_bundle
from grimml_train.features.extract import FEATURE_COLUMNS
from grimml_train.ingest import ingest, load_ingest_table
from grimml_train.inventory.report import inventory_report
from grimml_train.labels.join import join_labels
from grimml_train.run import write_run_meta
from grimml_train.split.splits import grouped_split, leave_one_out, require_operator_for_cheat
from grimml_train.train.lgbm import threshold_on_val, train_fold, transform, _xy


VALIDITY_COLS = [
    "valid_partial",
    "valid_lag",
    "valid_death",
    "valid_respawn",
    "valid_teleport",
    "valid_setback",
    "valid_world",
    "valid_gamemode",
    "valid_vanish",
]


def main() -> None:
    p = argparse.ArgumentParser(prog="grimml-train")
    p.add_argument("--config", type=Path, default=Path("config.example.yaml"))
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("ingest", "decode", "label", "inventory", "features", "train", "eval", "export", "pipeline"):
        sub.add_parser(name)
    args = p.parse_args()
    cfg = load_config(args.config)
    np.random.seed(cfg.seed)
    cfg.out_dir.mkdir(parents=True, exist_ok=True)

    if args.cmd in ("ingest", "decode"):
        ingest(cfg)
        write_run_meta(cfg, {"stage": "ingest"})
        return
    if args.cmd == "label":
        _cmd_label(cfg)
        return
    if args.cmd == "inventory":
        print(inventory_report(_labeled(cfg), cfg.out_dir / "inventory.txt"))
        return
    if args.cmd == "features":
        print("features are computed during ingest")
        return
    if args.cmd == "train":
        _cmd_train(cfg)
        return
    if args.cmd == "eval":
        print(json.dumps(_cmd_eval(cfg), indent=2, default=str))
        return
    if args.cmd == "export":
        _cmd_export(cfg)
        return
    if args.cmd == "pipeline":
        ingest(cfg)
        _cmd_label(cfg)
        print(inventory_report(_labeled(cfg), cfg.out_dir / "inventory.txt"))
        _cmd_train(cfg)
        print(json.dumps(_cmd_eval(cfg), indent=2, default=str))
        _cmd_export(cfg)
        return


def _labeled(cfg):
    path = cfg.out_dir / "labeled" / "windows.parquet"
    if not path.exists():
        raise SystemExit(f"missing {path}; run grimml-train label")
    return pd.read_parquet(path)


def _cmd_label(cfg) -> None:
    windows = load_ingest_table(cfg.out_dir, "windows")
    markers = load_ingest_table(cfg.out_dir, "markers")
    if windows.empty:
        raise SystemExit("no ingest windows; run grimml-train ingest")
    labeled = join_labels(
        windows,
        cfg.sidecar_dir,
        cfg.marker_trim_seconds,
        markers,
        exclude_interrupted=cfg.exclude_interrupted,
    )
    if not cfg.keep_unlabeled:
        labeled = labeled[labeled["label"] != "UNLABELED"]
    dest = cfg.out_dir / "labeled"
    dest.mkdir(parents=True, exist_ok=True)
    labeled.to_parquet(dest / "windows.parquet", index=False)
    print(f"labeled {len(labeled)} {labeled['label'].value_counts().to_dict()}")
    write_run_meta(cfg, {"stage": "label"})


def _train_table(cfg) -> pd.DataFrame:
    w = _labeled(cfg)
    if cfg.exclude_flagged_from_train:
        for c in VALIDITY_COLS:
            if c in w.columns:
                w = w[w[c].fillna(False) != True]
    return w


def _cmd_train(cfg) -> None:
    w = _train_table(cfg)
    require_operator_for_cheat(w[w["label"] == "CHEAT"]) if "label" in w.columns else None
    train, val = grouped_split(w, group_col="anon_player_id", test_size=0.2, seed=cfg.seed)
    clf, scaler, cols = train_fold(train, cfg.seed)
    thr = threshold_on_val(clf, scaler, val, cols, cfg.max_fpr)
    clf.booster_.save_model(str(cfg.out_dir / "model.txt"))
    np.savez(cfg.out_dir / "scaler.npz", mean=scaler.mean_, scale=scaler.scale_)
    meta = {"threshold": thr, "feature_names": cols}
    (cfg.out_dir / "train_meta.json").write_text(json.dumps(meta), encoding="utf-8")
    print(f"model -> {cfg.out_dir / 'model.txt'} threshold={thr}")
    write_run_meta(cfg, {"stage": "train", **meta})


def _cmd_eval(cfg) -> dict:
    w = _train_table(cfg)
    require_operator_for_cheat(w)
    train, test = grouped_split(w, group_col="anon_player_id", seed=cfg.seed)
    inner_tr, val = grouped_split(train, group_col="anon_player_id", test_size=0.25, seed=cfg.seed + 1)
    clf, scaler, cols = train_fold(inner_tr, cfg.seed)
    thr = threshold_on_val(clf, scaler, val, cols, cfg.max_fpr)
    rep = evaluate(clf, scaler, cols, test, thr)
    legit = w[w["label"] == "LEGIT_CONFIRMED"]
    probe = {}
    for target in ("is_test_account", "server_id", "map_id", "kit"):
        probe[target] = confound_probe(legit, [c for c in FEATURE_COLUMNS if c in legit.columns], target)
    if any(isinstance(v, float) and v > cfg.confound_auc_warn for v in probe.values() if v == v):
        same = w[w["is_test_account"] == True] if "is_test_account" in w.columns else w
        rep["confound_restriction"] = "restrict primary model to same test sessions"
        rep["same_session_windows"] = int(len(same))
    rep["confound_auc"] = probe
    loco = []
    for name, tr, te in leave_one_out(w, "mark_client_id"):
        c, s, cols2 = train_fold(tr, cfg.seed)
        thr2 = threshold_on_val(c, s, tr.sample(frac=0.2, random_state=cfg.seed) if len(tr) > 5 else tr, cols2, cfg.max_fpr)
        loco.append({"held_out": name, **evaluate(c, s, cols2, te, thr2)})
    looo = []
    for name, tr, te in leave_one_out(w, "operator"):
        c, s, cols2 = train_fold(tr, cfg.seed)
        looo.append({"held_out": name, "n_test": len(te)})
    rep["leave_one_client_out"] = loco
    rep["leave_one_operator_out"] = looo
    (cfg.out_dir / "eval.json").write_text(json.dumps(rep, indent=2, default=str), encoding="utf-8")
    write_run_meta(cfg, {"stage": "eval"})
    return rep


def _cmd_export(cfg) -> None:
    w = _train_table(cfg)
    train, val = grouped_split(w, seed=cfg.seed)
    clf, scaler, cols = train_fold(train, cfg.seed)
    thr = threshold_on_val(clf, scaler, val, cols, cfg.max_fpr)
    export_bundle(clf, scaler, cols, val if len(val) else train, cfg.out_dir / "export", thr)
    print(f"export -> {cfg.out_dir / 'export'}")
    write_run_meta(cfg, {"stage": "export"})


if __name__ == "__main__":
    main()
