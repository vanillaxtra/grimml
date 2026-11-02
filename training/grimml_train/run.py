from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

from grimml_train.config import TrainConfig


def git_commit(cwd: Path) -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=cwd, text=True).strip()
    except Exception:
        return "unknown"


def manifest_hash(recordings_dir: Path) -> str:
    h = hashlib.sha256()
    if not recordings_dir.exists():
        return h.hexdigest()
    files = sorted(p for p in recordings_dir.rglob("*.gmlml"))
    for p in files[:50000]:
        st = p.stat()
        h.update(str(p.name).encode())
        h.update(str(st.st_size).encode())
        h.update(str(int(st.st_mtime)).encode())
    h.update(str(len(files)).encode())
    return h.hexdigest()


def write_run_meta(cfg: TrainConfig, extra: dict) -> Path:
    dest = cfg.out_dir / "run_meta.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "seed": cfg.seed,
        "config": cfg.model_dump(mode="json"),
        "git_commit": git_commit(Path(__file__).resolve().parents[1]),
        "data_manifest_hash": manifest_hash(cfg.recordings_dir),
        **extra,
    }
    dest.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    return dest
