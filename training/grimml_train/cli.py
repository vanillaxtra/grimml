from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.config import load_config
from grimml_train.eval.metrics import eval_binary
from grimml_train.export.onnx_export import export_bundle
from grimml_train.ingest import ingest, load_ingest_table
from grimml_train.inventory.report import inventory_report
from grimml_train.labels.join import join_labels
from grimml_train.split.splits import grouped_train_test_split
from grimml_train.train.lgbm import train_baseline


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
        return
    if args.cmd == "label":
        _cmd_label(cfg)
        return
    if args.cmd == "inventory":
        w = _labeled(cfg)
        print(inventory_report(w, cfg.out_dir / "inventory.txt"))
        return
    if args.cmd == "features":
        print("features are computed during ingest; nothing to do")
        return
    if args.cmd == "train":
        _cmd_train(cfg)
        return
    if args.cmd == "eval":
        _cmd_eval(cfg)
        return
    if args.cmd == "export":
        feat, labels = _xy_tables(cfg)
        clf = train_baseline(feat, labels, cfg.seed)
        export_bundle(clf, feat, labels, cfg.out_dir / "export")
        print(f"export -> {cfg.out_dir / 'export'}")
        return
    if args.cmd == "pipeline":
        ingest(cfg)
        _cmd_label(cfg)
        w = _labeled(cfg)
        print(inventory_report(w, cfg.out_dir / "inventory.txt"))
        _cmd_train(cfg)
        _cmd_eval(cfg)
        return


def _labeled(cfg) -> pd.DataFrame:
    path = cfg.out_dir / "labeled" / "windows.parquet"
    if not path.exists():
        raise SystemExit(f"missing {path}; run grimml-train label")
    return pd.read_parquet(path)


def _cmd_label(cfg) -> None:
    windows = load_ingest_table(cfg.out_dir, "windows")
    markers = load_ingest_table(cfg.out_dir, "markers")
    if windows.empty:
        raise SystemExit("no ingest windows; run grimml-train ingest")
    labeled = join_labels(windows, cfg.sidecar_dir, cfg.marker_trim_seconds, markers)
    if not cfg.keep_unlabeled:
        labeled = labeled[labeled["label"] != "UNLABELED"]
    dest = cfg.out_dir / "labeled"
    dest.mkdir(parents=True, exist_ok=True)
    labeled.to_parquet(dest / "windows.parquet", index=False)
    n = labeled["label"].value_counts().to_dict() if "label" in labeled else {}
    print(f"labeled {len(labeled)} windows {n}")


def _xy_tables(cfg):
    from grimml_train.features.extract import FEATURE_COLUMNS

    w = _labeled(cfg)
    cols = ["window_key"] + [c for c in FEATURE_COLUMNS if c in w.columns]
    feat = w[cols].copy()
    labels = w[["window_key", "label", "label_source", "anon_player_id"]].copy()
    if "is_test_account" in w.columns:
        labels["is_test_account"] = w["is_test_account"]
    if "confirmed_label" in w.columns:
        labels["confirmed_label"] = w["confirmed_label"]
    return feat, labels


def _cmd_train(cfg) -> None:
    feat, labels = _xy_tables(cfg)
    clf = train_baseline(feat, labels, cfg.seed)
    clf.booster_.save_model(str(cfg.out_dir / "model.txt"))
    print(f"model -> {cfg.out_dir / 'model.txt'}")


def _cmd_eval(cfg) -> None:
    from grimml_train.train.lgbm import _prep_xy

    feat, labels = _xy_tables(cfg)
    merged = feat.merge(labels, on="window_key")
    merged = merged[merged["label"].isin(["CHEAT", "LEGIT_CONFIRMED"])]
    if merged.empty:
        raise SystemExit("no CHEAT/LEGIT_CONFIRMED rows to eval")
    tr, te = grouped_train_test_split(merged, "anon_player_id", seed=cfg.seed)
    train_df = merged.iloc[tr]
    test_df = merged.iloc[te]
    clf = train_baseline(train_df, train_df, cfg.seed)
    x_te, y_te, _, _ = _prep_xy(test_df, test_df)
    print(eval_binary(y_te, clf.predict(x_te)))


if __name__ == "__main__":
    main()
