from __future__ import annotations

from pathlib import Path

import yaml
from pydantic import BaseModel


class TrainConfig(BaseModel):
    seed: int = 42
    recordings_dir: Path = Path("../plugins/grimml/recordings")
    sidecar_dir: Path = Path("../plugins/grimml")
    out_dir: Path = Path("out")
    marker_trim_seconds: float = 3.0
    exclude_interrupted: bool = True
    exclude_flagged_from_train: bool = True
    allow_probable_negatives: bool = False
    grim_checks_json: Path | None = None
    exclude_grim_features_when_weak_labels: bool = True
    test_client_holdout: str | None = None
    synthetic_source_tag: str = "synthetic"
    workers: int = 0
    shard_size: int = 4000
    keep_unlabeled: bool = True
    keep_frames: bool = False
    skip_bad_files: bool = True
    resume: bool = True
    max_fpr: float = 0.01
    confound_auc_warn: float = 0.6


def load_config(path: Path) -> TrainConfig:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return TrainConfig.model_validate(data)
