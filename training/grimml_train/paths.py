"""Default locations. Everything is relative to the training folder, so commands work from any directory."""
from __future__ import annotations

from pathlib import Path

TRAINING_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = TRAINING_DIR.parent

CONFIG_DIR = TRAINING_DIR / "config"
SESSIONS_CSV = CONFIG_DIR / "sessions.csv"
LABELS_YAML = CONFIG_DIR / "labels.yaml"
MODULES_YAML = CONFIG_DIR / "modules.yaml"

DATA_DIR = TRAINING_DIR / "prestige temp test"  # the labelled .gmlml recordings
TELEMETRY_DIR = TRAINING_DIR / "telemetry" / "telemetry-data"  # uploads copied from the receiver (git-ignored)
RUNS_DIR = TRAINING_DIR / "runs"                # one folder per training run (git-ignored)
CACHE_DIR = TRAINING_DIR / ".cache"             # decoded windows (git-ignored)

MODELS_DIR = REPO_DIR / "models"                # what the detector plugin downloads
PARITY_DIR = REPO_DIR / "detector" / "src" / "test" / "resources" / "parity"
