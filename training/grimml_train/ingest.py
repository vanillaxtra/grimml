from __future__ import annotations

import json
import os
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from pathlib import Path

import pandas as pd

from grimml_train.config import TrainConfig
from grimml_train.decode.window import decode_file
from grimml_train.features.extract import features_from_decoded


def iter_gmlml(root: Path):
    root = Path(root)
    if root.is_file() and root.suffix == ".gmlml":
        yield root
        return
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if name.endswith(".gmlml"):
                yield Path(dirpath) / name


def _process_path(path_str: str) -> dict:
    path = Path(path_str)
    try:
        decoded = decode_file(path)
    except Exception as e:
        return {"error": f"{path}: {e}", "path": path_str}
    feat = features_from_decoded(decoded)
    win = decoded["window"]
    if "label_source" in win:
        win["header_label_source"] = win.pop("label_source")
    win.update(feat)
    win["path"] = path_str
    return {"window": win, "markers": decoded.get("markers") or [], "path": path_str, "error": None}


def ingest(cfg: TrainConfig) -> dict:
    out = cfg.out_dir / "ingest"
    win_dir = out / "windows"
    mk_dir = out / "markers"
    win_dir.mkdir(parents=True, exist_ok=True)
    mk_dir.mkdir(parents=True, exist_ok=True)
    err_path = out / "errors.log"
    done_path = out / "done.txt"

    done: set[str] = set()
    if cfg.resume and done_path.exists():
        done = set(done_path.read_text(encoding="utf-8").splitlines())

    files = [str(p.resolve()) for p in iter_gmlml(cfg.recordings_dir)]
    pending = [p for p in files if p not in done]
    workers = cfg.workers or (os.cpu_count() or 2)
    inflight_cap = max(workers * 8, 16)
    shard_n = len(list(win_dir.glob("part-*.parquet")))
    buf_w: list[dict] = []
    buf_m: list[dict] = []
    n_ok = 0
    n_err = 0
    n_bytes = 0

    def flush():
        nonlocal shard_n, buf_w, buf_m
        if not buf_w:
            return
        shard_n += 1
        pd.DataFrame(buf_w).to_parquet(win_dir / f"part-{shard_n:05d}.parquet", index=False)
        if buf_m:
            pd.DataFrame(buf_m).to_parquet(mk_dir / f"part-{shard_n:05d}.parquet", index=False)
        with done_path.open("a", encoding="utf-8") as f:
            for row in buf_w:
                f.write(row["path"] + "\n")
        buf_w = []
        buf_m = []

    def handle(r: dict):
        nonlocal n_ok, n_err, n_bytes, buf_w, buf_m
        if r.get("error"):
            n_err += 1
            with err_path.open("a", encoding="utf-8") as f:
                f.write(r["error"] + "\n")
            if not cfg.skip_bad_files:
                raise RuntimeError(r["error"])
            return
        buf_w.append(r["window"])
        buf_m.extend(r["markers"])
        n_ok += 1
        try:
            n_bytes += Path(r["path"]).stat().st_size
        except OSError:
            pass
        if len(buf_w) >= cfg.shard_size:
            flush()

    if pending and workers <= 1:
        for i, p in enumerate(pending, 1):
            handle(_process_path(p))
            if i % 200 == 0:
                print(f"ingest {i}/{len(pending)} ok={n_ok} err={n_err}", flush=True)
        flush()
    elif pending:
        it = iter(pending)
        with ProcessPoolExecutor(max_workers=workers) as pool:
            futs = set()

            def fill():
                while len(futs) < inflight_cap:
                    try:
                        p = next(it)
                    except StopIteration:
                        return
                    futs.add(pool.submit(_process_path, p))

            fill()
            seen = 0
            while futs:
                finished, futs = wait(futs, return_when=FIRST_COMPLETED)
                for fut in finished:
                    handle(fut.result())
                    seen += 1
                    if seen % 200 == 0:
                        print(f"ingest {seen}/{len(pending)} ok={n_ok} err={n_err}", flush=True)
                fill()
        flush()

    summary = {
        "files_seen": len(files),
        "pending": len(pending),
        "ok": n_ok,
        "errors": n_err,
        "shards": shard_n,
        "bytes": n_bytes,
        "workers": workers,
        "already_done": len(done),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(summary, flush=True)
    return summary


def load_ingest_table(out_dir: Path, name: str) -> pd.DataFrame:
    folder = out_dir / "ingest" / name
    if not folder.exists():
        return pd.DataFrame()
    parts = sorted(folder.glob("part-*.parquet"))
    if not parts:
        return pd.DataFrame()
    return pd.concat([pd.read_parquet(p) for p in parts], ignore_index=True)
