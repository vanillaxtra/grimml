"""From .gmlml files to labelled, usable windows.

decode_folder -> label_windows -> assign_blocks -> (per module) match_activity

Decoding is the slow part, so decoded windows are cached in .cache/ per file (path, size, mtime) and per version of
the decode/feature code: a new recording or a feature change only decodes what changed.
"""
from __future__ import annotations

import hashlib
import os
import pickle
import random
import re
import time
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from grimml_train.catalog import CHEAT, LEGIT, LabelRules

MARK_RE = re.compile(r"_(m[0-9a-fA-F]{8})_")
BULK_ID = "bulk"  # pseudo mark id for sampled bulk legit windows
HARD_INVALID = ["valid_death", "valid_respawn", "valid_teleport", "valid_setback", "valid_world", "valid_gamemode",
                "valid_vanish"]
ATTACK_EDGES = [-1, 0, 2, 5, 10, 1e9]
CACHE_FORMAT = 1


# ---------------------------------------------------------------------------------------------------- decoding

def find_files(folder: Path) -> list[Path]:
    return sorted(Path(folder).rglob("*.gmlml"))


def decode_one(path_str: str) -> dict:
    """One file -> one window row (header fields, core features, schema 4 features). Never raises."""
    from grimml_train.decode.window import decode_file
    from grimml_train.features.extract import features_from_decoded
    from grimml_train.features.schema4 import schema4_features

    path = Path(path_str)
    try:
        decoded = decode_file(path)
    except Exception as e:  # a broken file must not stop the run; it is reported instead
        return {"path": path_str, "error": f"{type(e).__name__}: {e}"}
    win = dict(decoded["window"])
    if "label_source" in win:
        win["header_label_source"] = win.pop("label_source")
    win.update(features_from_decoded(decoded))
    win.update(schema4_features(decoded))  # NaN on schema 1-3 except the click_* timing features
    win["path"] = path_str
    return {"path": path_str, "window": win, "error": None}


def code_fingerprint() -> str:
    """Changes whenever the decode or feature code changes, so stale cached features are never used."""
    root = Path(__file__).resolve().parent
    h = hashlib.sha256(str(CACHE_FORMAT).encode())
    for p in sorted([*(root / "decode").glob("*.py"), *(root / "features").glob("*.py")]):
        h.update(p.name.encode())
        h.update(p.read_bytes())
    return h.hexdigest()[:16]


def _pool_map(fn, items: list, workers: int) -> list:
    if workers <= 0:
        workers = max(1, min(8, (os.cpu_count() or 2) - 1))
    if workers == 1 or len(items) < 200:
        return [fn(i) for i in items]
    with ProcessPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(fn, items, chunksize=16))


def decode_folder(folder: Path, *, workers: int = 0, paths: list[Path] | None = None,
                  cache_dir: Path | None = None) -> tuple[pd.DataFrame, list[tuple[str, str]]]:
    """Decoded windows (one row per file, in path order) and the files that could not be read."""
    paths = find_files(folder) if paths is None else list(paths)
    keys = {}
    for p in paths:
        st = p.stat()
        keys[str(p)] = (st.st_size, st.st_mtime_ns)

    cache: dict[str, tuple[tuple, dict]] = {}
    cache_file = None
    if cache_dir is not None:
        cache_file = Path(cache_dir) / f"windows_{code_fingerprint()}.pkl"
        if cache_file.exists():
            with open(cache_file, "rb") as f:
                cache = pickle.load(f)

    todo = [p for p, k in keys.items() if p not in cache or cache[p][0] != k]
    if todo:
        t0 = time.time()
        print(f"decoding {len(todo)} files ({len(keys) - len(todo)} cached) ...", flush=True)
        for r in _pool_map(decode_one, todo, workers):
            cache[r["path"]] = (keys[r["path"]], r)
        print(f"decoded in {time.time() - t0:.0f}s", flush=True)
        if cache_file is not None:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            for old in cache_file.parent.glob("windows_*.pkl"):
                if old != cache_file:
                    old.unlink()  # cache from older decode/feature code
            tmp = cache_file.with_suffix(".tmp")
            with open(tmp, "wb") as f:
                pickle.dump(cache, f, protocol=pickle.HIGHEST_PROTOCOL)
            os.replace(tmp, cache_file)

    wins, errs = [], []
    for p in keys:
        r = cache[p][1]
        if r.get("error"):
            errs.append((p, r["error"]))
        else:
            wins.append(r["window"])
    return pd.DataFrame(wins), errs


# ---------------------------------------------------------------------------------------------------- bulk legit

def load_processed_bulk(win_dir: Path, holdout_file: Path) -> tuple[pd.DataFrame, str]:
    """Windows an earlier ingest already decoded (parquet). Live hold-out accounts are dropped."""
    parts = sorted(Path(win_dir).glob("*.parquet"))
    if not parts:
        raise SystemExit(f"no parquet files in {win_dir}")
    b = pd.concat((pd.read_parquet(p) for p in parts), ignore_index=True)
    if Path(holdout_file).exists():
        hold = {x.strip() for x in Path(holdout_file).read_text(encoding="utf8").splitlines() if x.strip()}
        b = b[~b["anon_player_id"].astype(str).isin(hold)]
    return b.reset_index(drop=True), f"{len(parts)} parquet parts"


def sample_bulk(root: Path, n: int, seed: int, listing_cache: Path) -> list[Path]:
    """Random sample of n unmarked files from a bulk dump. The directory listing (slow on millions of files) is
    cached once; only the sampled files are decoded."""
    if listing_cache.exists():
        files = listing_cache.read_text(encoding="utf8").splitlines()
    else:
        files, stack = [], [str(root)]
        print("listing the bulk folder once (cached afterwards) ...", flush=True)
        while stack:
            with os.scandir(stack.pop()) as it:
                for e in it:
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.name.endswith(".gmlml"):
                        files.append(e.path)
        listing_cache.parent.mkdir(parents=True, exist_ok=True)
        listing_cache.write_text("\n".join(files), encoding="utf8")
    files = [f for f in files if not MARK_RE.search(os.path.basename(f))]
    random.Random(seed).shuffle(files)
    return [Path(f) for f in files[:n]]


def mark_bulk(w: pd.DataFrame, per_player: int, seed: int) -> pd.DataFrame:
    """Bulk windows: same file format as the cheat files only, known role only, at most per_player per account."""
    w = w.copy()
    isb = w["mark_id"] == BULK_ID
    schemas = set(w.loc[(w["label"] == CHEAT) & ~isb, "schema_version"].dropna().unique())
    w.loc[isb & (w["reason"] == "") & ~num(w, "schema_version").isin(schemas), "reason"] = \
        "bulk: older file format than the cheat files"
    w.loc[isb & (w["reason"] == "") & num(w, "role").isna(), "reason"] = "bulk: role unknown"
    cand = w[isb & (w["reason"] == "")].sample(frac=1.0, random_state=seed)
    over = cand.groupby("anon_player_id").cumcount() >= per_player
    w.loc[over[over].index, "reason"] = "bulk: per-player cap"
    w["use"] = w["reason"] == ""
    return w


# ---------------------------------------------------------------------------------------------------- labelling

def num(df: pd.DataFrame, col: str) -> pd.Series:
    if col in df.columns:
        return pd.to_numeric(df[col], errors="coerce")
    return pd.Series(np.nan, index=df.index)


def label_windows(w: pd.DataFrame, sessions: pd.DataFrame, *, trim_s: float, roles: tuple[int, ...],
                  schema4_only: bool, validity_fixed_from_ms: int | None, hard_invalid: str = "auto") -> pd.DataFrame:
    """Attach label/module/client from the file-name mark id and give every unusable window a reason.

    hard_invalid: "auto" filters death/teleport/... windows recorded by a fixed recorder build only, "keep" never
    filters, "drop" always filters.
    """
    w = w.copy()
    epoch = num(w, "epoch_ms")
    has_epoch = epoch > 1e12
    w["win_len_ms"] = (num(w, "window_end_nanos") - num(w, "window_start_nanos")) / 1e6
    w["ts_ms"] = np.where(has_epoch, epoch, num(w, "window_start_nanos") / 1e6)
    w["has_epoch"] = has_epoch

    m = sessions[["mark_id", "label", "client", "module", "settings", "start_ms", "end_ms", "exclude_reason"]]
    m = m.add_prefix("id_").rename(columns={"id_mark_id": "mark_id"})
    w = w.merge(m, on="mark_id", how="left")
    w["label"] = w["id_label"]

    reason = pd.Series("", index=w.index, dtype=object)

    def mark(mask, text):
        mask = mask & (reason == "")
        reason[mask] = text

    mark(w["mark_id"].isna(), "no mark id in file name")
    mark(w["mark_id"].notna() & w["id_label"].isna(), "mark id not in sessions.csv")
    excl = w["id_exclude_reason"].fillna("")
    mark(excl != "", "segment: " + excl)
    if roles:
        role = num(w, "role")
        mark(role.notna() & ~role.isin(roles), "role not in roles")
    if schema4_only:
        # schema 4 features are NaN on older windows: mixing schema 3 legit with schema 4 cheat would let the model
        # learn "has schema 4 data = cheat", so both classes come from schema 4 windows only.
        mark(num(w, "schema_version").fillna(0) < 4, "not schema 4")
    trim = trim_s * 1000.0
    ws = epoch
    we = epoch + w["win_len_ms"].fillna(7000.0)
    outside = has_epoch & w["id_start_ms"].notna() & ((ws < w["id_start_ms"] + trim) | (we > w["id_end_ms"] - trim))
    mark(outside, "outside the trimmed segment")
    if hard_invalid == "drop":
        trust = pd.Series(True, index=w.index)
    elif hard_invalid == "keep" or validity_fixed_from_ms is None:
        trust = pd.Series(False, index=w.index)
    else:
        trust = has_epoch & (epoch >= validity_fixed_from_ms)
    w["validity_trusted"] = trust
    if trust.any():
        bad = pd.Series(False, index=w.index)
        for c in HARD_INVALID:
            if c in w.columns:
                bad |= w[c].fillna(False).astype(bool)
        mark(bad & trust, "hard validity flag (death/teleport/setback/...)")
    w["reason"] = reason
    w["use"] = reason == ""
    return w


def assign_blocks(d: pd.DataFrame, rules: LabelRules, chunk_min: float, gap_s: float,
                  bulk_groups: int = 8) -> tuple[pd.DataFrame, int]:
    """Held-out blocks. Cheat block = one session. Legit block = a time chunk of one recording (both accounts of a
    duel share it), or a group of whole bulk players. Returns the windows kept and how many fell in a chunk gap."""
    d = d.copy()
    d["block"] = d["mark_id"]
    keep = pd.Series(True, index=d.index)
    leg = d[d["label"] == LEGIT]
    for mid, g in leg.groupby(leg["mark_id"].map(rules.fight_group)):
        if mid == BULK_ID:
            grp = g["anon_player_id"].astype(str).map(lambda a: zlib.crc32(a.encode()) % bulk_groups)
            d.loc[g.index, "block"] = [f"bulk:{i}" for i in grp]
            continue
        t = g["ts_ms"]
        span = float(t.max() - t.min())
        n = max(1, int(round(span / (chunk_min * 60_000)))) if span > 0 else 1
        width = span / n + 1.0
        rel = t - t.min()
        idx = np.minimum((rel // width).astype(int), n - 1)
        d.loc[g.index, "block"] = [f"{mid}:{i}" for i in idx]
        if gap_s > 0 and n > 1:
            pos = rel - idx * width
            drop = ((idx > 0) & (pos < gap_s * 1000.0)).to_numpy()
            keep.loc[g.index[drop]] = False
    return d[keep], int((~keep).sum())


def match_activity(cheat: pd.DataFrame, legit: pd.DataFrame, cols: list[str], seed: int):
    """Subsample both classes to the same number of windows in every activity cell (attack_count bins x
    event_count terciles), so 'how busy the window was' can no longer separate them."""
    cols = [c for c in cols if c in cheat.columns and c in legit.columns]
    if not cols or cheat.empty or legit.empty:
        return cheat, legit, {"cells": 0, "note": "no activity columns"}
    both = pd.concat([cheat, legit])
    cell = pd.Series("", index=both.index, dtype=object)
    for c in cols:
        v = pd.to_numeric(both[c], errors="coerce")
        if c == "attack_count":
            b = pd.cut(v, ATTACK_EDGES, labels=False)
        else:
            b = pd.qcut(v.rank(method="first"), 3, labels=False)
        cell = cell + "|" + b.astype("Int64").astype(str)
    rng = np.random.default_rng(seed)
    keep_c, keep_l, used = [], [], 0
    for _, idx in both.groupby(cell).groups.items():
        ic = [i for i in idx if i in cheat.index]
        il = [i for i in idx if i in legit.index]
        n = min(len(ic), len(il))
        if n == 0:
            continue
        used += 1
        keep_c += list(rng.choice(ic, n, replace=False))
        keep_l += list(rng.choice(il, n, replace=False))
    stats = {"cells": used, "cols": cols, "cheat_before": len(cheat), "cheat_after": len(keep_c),
             "legit_before": len(legit), "legit_after": len(keep_l)}
    return cheat.loc[keep_c], legit.loc[keep_l], stats
