from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pandas as pd
import yaml

from grimml_train.config import BulkLabel, TrainConfig

BULK_SOURCE = "operator_asserted_bulk"
BULK_PRIORITY = 1


def config_hash(bulk_labels: list[BulkLabel]) -> str:
    blob = yaml.safe_dump([b.model_dump(mode="json") for b in bulk_labels], sort_keys=True)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def under_dir(path: str, folder: Path | None) -> bool:
    if folder is None or not path:
        return False
    try:
        Path(path).resolve().relative_to(Path(folder).resolve())
        return True
    except ValueError:
        return False


def bulk_table_path(out_dir: Path) -> Path:
    return out_dir / "labels" / "bulk.parquet"


def labels_table_path(out_dir: Path) -> Path:
    return out_dir / "labels" / "labels.parquet"


def apply_bulk(
    windows: pd.DataFrame,
    cfg: TrainConfig,
) -> pd.DataFrame:
    out = windows.copy()
    if "label" not in out.columns:
        out["label"] = "UNLABELED"
    if "label_source" not in out.columns:
        out["label_source"] = "unlabeled"
    if "label_priority" not in out.columns:
        out["label_priority"] = 0
    out["operator_account"] = False
    out["pair_group"] = "live"
    if "asserted_by" not in out.columns:
        out["asserted_by"] = ""
    if "label_note" not in out.columns:
        out["label_note"] = ""
    if "bulk_config_hash" not in out.columns:
        out["bulk_config_hash"] = ""

    cheat_anons = set()
    if "label" in out.columns:
        cheat_anons = set(out.loc[out["label"] == "CHEAT", "anon_player_id"].dropna().tolist())

    h = config_hash(cfg.bulk_labels)
    for spec in cfg.bulk_labels:
        if "path" not in out.columns:
            continue
        pri = BULK_PRIORITY
        in_bulk = out["path"].map(lambda p: under_dir(str(p), spec.path))
        if cfg.cheat_dir:
            in_cheat = out["path"].map(lambda p: under_dir(str(p), cfg.cheat_dir))
        else:
            in_cheat = pd.Series(False, index=out.index)
        pick = in_bulk & ~in_cheat & (out["label_priority"] < pri)
        out.loc[pick, "label"] = spec.label
        out.loc[pick, "label_source"] = spec.source
        out.loc[pick, "label_priority"] = pri
        out.loc[pick, "asserted_by"] = spec.asserted_by
        out.loc[pick, "label_note"] = spec.note
        out.loc[pick, "bulk_config_hash"] = h

    if cheat_anons:
        op = out["anon_player_id"].isin(cheat_anons)
        out.loc[op, "operator_account"] = True
        out.loc[op, "pair_group"] = "operator"
    out["confirmed_label"] = out["label"].isin({"CHEAT", "LEGIT_CONFIRMED"})
    return out


def write_labels_table(windows: pd.DataFrame, out_dir: Path) -> Path:
    cols = [
        "window_key",
        "window_id",
        "anon_player_id",
        "label",
        "label_source",
        "label_priority",
        "asserted_by",
        "label_note",
        "bulk_config_hash",
        "operator_account",
        "pair_group",
    ]
    have = [c for c in cols if c in windows.columns]
    table = windows[have].copy()
    dest = labels_table_path(out_dir)
    dest.parent.mkdir(parents=True, exist_ok=True)
    table.to_parquet(dest, index=False)
    bulk = table[table["label_source"] == BULK_SOURCE] if "label_source" in table.columns else table.iloc[0:0]
    bulk.to_parquet(bulk_table_path(out_dir), index=False)
    meta = dest.parent / "bulk_meta.json"
    meta.write_text(
        json.dumps(
            {
                "source": BULK_SOURCE,
                "rows": int(len(bulk)),
                "config_hash": str(bulk["bulk_config_hash"].iloc[0]) if len(bulk) and "bulk_config_hash" in bulk.columns else "",
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    return dest


def undo_bulk(out_dir: Path) -> int:
    bp = bulk_table_path(out_dir)
    n = 0
    if bp.exists():
        n = len(pd.read_parquet(bp))
        bp.unlink()
    lp = labels_table_path(out_dir)
    if lp.exists():
        lab = pd.read_parquet(lp)
        if "label_source" in lab.columns:
            lab = lab[lab["label_source"] != BULK_SOURCE]
            lab.to_parquet(lp, index=False)
    meta = out_dir / "labels" / "bulk_meta.json"
    if meta.exists():
        meta.unlink()
    return n
