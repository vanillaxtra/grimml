from __future__ import annotations

import csv
import json
import os
import random
import shutil
import time
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from pathlib import Path

import pandas as pd

from grimml_train.config import TrainConfig
from grimml_train.decode.reader import decode_file
from grimml_train.features.extract import features_from_decoded

LEGACY_INGEST = Path(__file__).resolve().parents[1] / "out" / "ingest"


def iter_gmlml_stat(root: Path):
    # yield (path_str, stat). DirEntry.stat on windows is free (find-data)
    root = Path(root)
    if root.is_file() and root.suffix == ".gmlml":
        try:
            yield str(root), root.stat()
        except OSError:
            return
        return
    stack = [str(root)]
    while stack:
        d = stack.pop()
        try:
            it = os.scandir(d)
        except OSError:
            continue
        with it:
            for e in it:
                try:
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.name.endswith(".gmlml"):
                        yield e.path, e.stat(follow_symlinks=False)
                except OSError:
                    continue


def iter_gmlml(root: Path):
    for p, _st in iter_gmlml_stat(root):
        yield Path(p)


def worker_count(cfg: TrainConfig) -> int:
    if cfg.workers and cfg.workers > 0:
        return cfg.workers
    return max(1, (os.cpu_count() or 2) - 1)


def path_meta(path: Path, root: Path) -> tuple[str, str]:
    tag, folder = path_meta_str(str(path), os.path.normcase(os.path.abspath(str(root))))
    return tag, folder


def path_meta_str(path_s: str, root_abs_norm: str) -> tuple[str, str]:
    s = os.path.normcase(os.path.normpath(path_s))
    pref = root_abs_norm if root_abs_norm.endswith(os.sep) else root_abs_norm + os.sep
    if not s.startswith(pref):
        parent = os.path.dirname(s)
        return "", parent
    rest = s[len(pref):]
    parts = rest.split(os.sep)
    if len(parts) <= 1:
        return "", ""
    folder = os.sep.join(parts[:-1])
    return parts[0], folder


def is_recent_mtime(mtime: float, skip_recent_minutes: int, now: float) -> bool:
    if skip_recent_minutes <= 0:
        return False
    return mtime > now - skip_recent_minutes * 60


def is_recent(path: Path, skip_recent_minutes: int, now: float | None = None) -> bool:
    if skip_recent_minutes <= 0:
        return False
    now = time.time() if now is None else now
    try:
        return is_recent_mtime(path.stat().st_mtime, skip_recent_minutes, now)
    except OSError:
        return False


def categorize_error(name: str, msg: str) -> str:
    blob = (name + " " + msg).lower()
    if "truncated" in blob:
        return "truncated"
    if "unknownrecordtype" in blob or "unknown record type" in blob:
        return "unknown_type"
    if any(s in blob for s in ("oserror", "permission", "filenotfound", "ioerror")):
        return "io_error"
    return "corrupt"


def parse_shard_range(text: str | None) -> tuple[int, int | None]:
    if not text:
        return 0, None
    a, b = text.split(":", 1)
    start = int(a) if a else 0
    end = int(b) if b else None
    return start, end


def _process_path(args: tuple[str, bool, str]) -> dict:
    path_str, keep_frames, root = args
    path = Path(path_str)
    try:
        decoded = decode_file(path)
    except Exception as e:
        return {
            "error": f"{type(e).__name__}: {e}",
            "category": categorize_error(type(e).__name__, str(e)),
            "path": path_str,
        }
    feat = features_from_decoded(decoded)
    win = decoded["window"]
    if "label_source" in win:
        win["header_label_source"] = win.pop("label_source")
    win.update(feat)
    win["path"] = path_str
    tag, folder = path_meta_str(path_str, os.path.normcase(os.path.abspath(root)))
    win["server_tag"] = tag
    win["source_folder"] = folder
    try:
        win["file_bytes"] = path.stat().st_size
    except OSError:
        win["file_bytes"] = 0
    return {
        "window": win,
        "markers": decoded.get("markers") or [],
        "frames": (decoded.get("frames") or []) if keep_frames else [],
        "events": (decoded.get("events") or []) if keep_frames else [],
        "path": path_str,
        "error": None,
    }


PROD_INGEST = Path("E:/recordings_processed") / "ingest"


def _seed_legacy(out: Path) -> None:
    # only copy the old training/out ingest into the production processed dir
    try:
        if out.resolve() != PROD_INGEST.resolve():
            return
    except OSError:
        return
    if (out / "done.txt").exists() or not (LEGACY_INGEST / "done.txt").exists():
        return
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(LEGACY_INGEST / "done.txt", out / "done.txt")
    for name in ("windows", "markers"):
        src = LEGACY_INGEST / name
        dest = out / name
        if src.exists() and not dest.exists():
            shutil.copytree(src, dest)


def scan_recordings(cfg: TrainConfig, *, now: float | None = None) -> dict:
    root = Path(cfg.recordings_dir)
    root_abs = os.path.normcase(os.path.abspath(str(root)))
    now = time.time() if now is None else now
    folders: dict[str, int] = {}
    n = 0
    n_recent = 0
    nbytes = 0
    for p, st in iter_gmlml_stat(root):
        if is_recent_mtime(st.st_mtime, cfg.skip_recent_minutes, now):
            n_recent += 1
            continue
        n += 1
        nbytes += st.st_size
        tag, _ = path_meta_str(p, root_abs)
        key = tag or "(root)"
        folders[key] = folders.get(key, 0) + 1
    return {"files": n, "bytes": nbytes, "skipped_recent": n_recent, "folders": folders}


def dry_run(cfg: TrainConfig, sample_n: int = 2000) -> dict:
    root = Path(cfg.recordings_dir)
    root_abs = os.path.normcase(os.path.abspath(str(root)))
    print(f"dry-run listing {root} (streaming scandir)", flush=True)
    workers = worker_count(cfg)
    rng = random.Random(cfg.seed)
    reservoir: list[str] = []
    folders: dict[str, int] = {}
    n = 0
    n_recent = 0
    nbytes = 0
    now = time.time()
    last_print = time.monotonic()
    t_scan = last_print
    for p, st in iter_gmlml_stat(root):
        if is_recent_mtime(st.st_mtime, cfg.skip_recent_minutes, now):
            n_recent += 1
            continue
        n += 1
        nbytes += st.st_size
        tag, _ = path_meta_str(p, root_abs)
        key = tag or "(root)"
        folders[key] = folders.get(key, 0) + 1
        if len(reservoir) < sample_n:
            reservoir.append(p)
        else:
            j = rng.randrange(n)
            if j < sample_n:
                reservoir[j] = p
        if time.monotonic() - last_print >= 30:
            elapsed = max(time.monotonic() - t_scan, 1e-6)
            print(
                f"dry-run scan {n} files {nbytes/1e9:.2f} GB skipped_recent={n_recent} {n/elapsed:.0f}/s",
                flush=True,
            )
            last_print = time.monotonic()
    print(
        f"dry-run scan done files={n} gb={nbytes/1e9:.3f} skipped_recent={n_recent} folders={len(folders)}",
        flush=True,
    )
    scan = {"files": n, "bytes": nbytes, "skipped_recent": n_recent, "folders": folders}
    t0 = time.monotonic()
    ok = 0
    err = 0
    sample_bytes = 0
    jobs = [(os.path.normpath(p), False, os.path.abspath(str(root))) for p in reservoir]
    print(f"dry-run decode sample n={len(jobs)} workers={workers}", flush=True)
    if jobs:
        with ProcessPoolExecutor(max_workers=min(workers, len(jobs))) as pool:
            for r in pool.map(_process_path, jobs, chunksize=8):
                if r.get("error"):
                    err += 1
                else:
                    ok += 1
                    try:
                        sample_bytes += Path(r["path"]).stat().st_size
                    except OSError:
                        pass
    elapsed = max(time.monotonic() - t0, 1e-6)
    rate = (ok + err) / elapsed
    per_worker = rate / max(workers, 1)
    remaining = scan["files"]
    eta_s = remaining / rate if rate > 0 else float("inf")
    bytes_per_file = (sample_bytes / ok) if ok else 4000
    # parquet is typically smaller than raw; use 0.6x raw as a ceiling from prior 20k run
    est_out = int(remaining * bytes_per_file * 0.8)
    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    disk = shutil.disk_usage(cfg.out_dir)
    report = {
        "files": scan["files"],
        "gb": round(scan["bytes"] / 1e9, 3),
        "skipped_recent": scan["skipped_recent"],
        "folders": scan["folders"],
        "sample": len(jobs),
        "sample_ok": ok,
        "sample_err": err,
        "files_per_s": round(rate, 2),
        "files_per_s_per_worker": round(per_worker, 2),
        "workers": workers,
        "eta_hours": round(eta_s / 3600, 2) if eta_s < 1e12 else None,
        "estimated_parquet_bytes": est_out,
        "estimated_parquet_gb": round(est_out / 1e9, 3),
        "free_bytes": disk.free,
        "free_gb": round(disk.free / 1e9, 2),
    }
    print(json.dumps(report, indent=2), flush=True)
    if disk.free < 2 * est_out:
        raise SystemExit(
            f"free disk {report['free_gb']} GB < 2x estimated output {report['estimated_parquet_gb']} GB"
        )
    return report


def ingest(
    cfg: TrainConfig,
    *,
    max_files: int | None = None,
    shard_range: str | None = None,
) -> dict:
    out = cfg.out_dir / "ingest"
    _seed_legacy(out)
    win_dir = out / "windows"
    mk_dir = out / "markers"
    fr_dir = out / "frames"
    ev_dir = out / "events"
    win_dir.mkdir(parents=True, exist_ok=True)
    mk_dir.mkdir(parents=True, exist_ok=True)
    if cfg.keep_frames:
        fr_dir.mkdir(parents=True, exist_ok=True)
        ev_dir.mkdir(parents=True, exist_ok=True)
    err_path = out / "errors.csv"
    done_path = out / "done.txt"

    done: set[str] = set()
    if cfg.resume and done_path.exists():
        done = set(done_path.read_text(encoding="utf-8").splitlines())

    shard_start, shard_end = parse_shard_range(shard_range)
    lo = shard_start * cfg.shard_size
    hi = None if shard_end is None else shard_end * cfg.shard_size
    if max_files is not None:
        hi = lo + max_files if hi is None else min(hi, lo + max_files)

    root = Path(cfg.recordings_dir)
    now = time.time()
    total = None if hi is None else max(hi - lo, 0)
    root_s = str(root.resolve())

    def job_iter():
        idx = 0
        for p, st in iter_gmlml_stat(root):
            path_str = os.path.normpath(str(p))
            if path_str in done:
                continue
            if is_recent_mtime(st.st_mtime, cfg.skip_recent_minutes, now):
                continue
            if idx < lo:
                idx += 1
                continue
            if hi is not None and idx >= hi:
                return
            idx += 1
            yield (path_str, cfg.keep_frames, root_s)

    workers = worker_count(cfg)
    inflight_cap = max(workers * 8, 16)
    shard_n = len(list(win_dir.glob("part-*.parquet")))
    buf_w: list[dict] = []
    buf_m: list[dict] = []
    buf_f: list[dict] = []
    buf_e: list[dict] = []
    n_ok = 0
    n_err = 0
    n_bytes = 0
    err_counts: dict[str, int] = {}
    t0 = time.monotonic()
    last_print = t0
    seen = 0

    if not err_path.exists():
        err_path.write_text("path,category,reason\n", encoding="utf-8")

    def flush():
        nonlocal shard_n, buf_w, buf_m, buf_f, buf_e
        if not buf_w:
            return
        shard_n += 1
        pd.DataFrame(buf_w).to_parquet(win_dir / f"part-{shard_n:05d}.parquet", index=False)
        if buf_m:
            pd.DataFrame(buf_m).to_parquet(mk_dir / f"part-{shard_n:05d}.parquet", index=False)
        if cfg.keep_frames and buf_f:
            pd.DataFrame(buf_f).to_parquet(fr_dir / f"part-{shard_n:05d}.parquet", index=False)
        if cfg.keep_frames and buf_e:
            pd.DataFrame(buf_e).to_parquet(ev_dir / f"part-{shard_n:05d}.parquet", index=False)
        with done_path.open("a", encoding="utf-8") as f:
            for row in buf_w:
                f.write(row["path"] + "\n")
        buf_w, buf_m, buf_f, buf_e = [], [], [], []

    def progress():
        elapsed = max(time.monotonic() - t0, 1e-6)
        rate = seen / elapsed
        if total is not None:
            left = max(total - seen, 0)
            eta = (left / rate / 60) if rate > 0 else 0
            tot_s, eta_s = str(total), f"{eta:.1f}m"
        else:
            tot_s, eta_s = "?", "?"
        print(
            f"ingest {seen}/{tot_s} ok={n_ok} err={n_err} "
            f"{rate:.1f}/s eta={eta_s} shards={shard_n} err_by={err_counts}",
            flush=True,
        )

    def handle(r: dict):
        nonlocal n_ok, n_err, n_bytes, buf_w, buf_m, buf_f, buf_e, seen, last_print
        seen += 1
        if r.get("error"):
            n_err += 1
            cat = r.get("category") or categorize_error("", r["error"])
            err_counts[cat] = err_counts.get(cat, 0) + 1
            with err_path.open("a", encoding="utf-8", newline="") as f:
                csv.writer(f).writerow([r["path"], cat, r["error"]])
            with done_path.open("a", encoding="utf-8") as f:
                f.write(r["path"] + "\n")
            if not cfg.skip_bad_files:
                raise RuntimeError(r["error"])
        else:
            buf_w.append(r["window"])
            buf_m.extend(r["markers"])
            buf_f.extend(r.get("frames") or [])
            buf_e.extend(r.get("events") or [])
            n_ok += 1
            try:
                n_bytes += Path(r["path"]).stat().st_size
            except OSError:
                pass
            if len(buf_w) >= cfg.shard_size:
                flush()
        if time.monotonic() - last_print >= 30:
            progress()
            last_print = time.monotonic()

    it = job_iter()
    if workers <= 1:
        for job in it:
            handle(_process_path(job))
        flush()
    else:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            futs = set()

            def fill():
                while len(futs) < inflight_cap:
                    try:
                        job = next(it)
                    except StopIteration:
                        return
                    futs.add(pool.submit(_process_path, job))

            fill()
            while futs:
                finished, futs = wait(futs, return_when=FIRST_COMPLETED)
                for fut in finished:
                    handle(fut.result())
                fill()
        flush()
    progress()

    summary = {
        "files_pending": seen,
        "ok": n_ok,
        "errors": n_err,
        "error_counts": err_counts,
        "shards": shard_n,
        "bytes": n_bytes,
        "workers": workers,
        "already_done": len(done),
        "errors_csv": str(err_path),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(summary, flush=True)
    return summary


def load_ingest_table(out_dir: Path, name: str, columns: list[str] | None = None) -> pd.DataFrame:
    folder = out_dir / "ingest" / name
    if not folder.exists():
        return pd.DataFrame()
    parts = sorted(folder.glob("part-*.parquet"))
    if not parts:
        return pd.DataFrame()
    frames = [pd.read_parquet(p, columns=columns) for p in parts]
    return pd.concat(frames, ignore_index=True)
