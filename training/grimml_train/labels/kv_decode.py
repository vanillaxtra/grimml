from __future__ import annotations

from pathlib import Path

import pandas as pd
import yaml

from grimml_train.decode.varint import hash_check_name


def _to_i32(v) -> int | None:
    if v is None or (isinstance(v, float) and v != v):
        return None
    v = int(v) & 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v


def candidate_strings(taxonomy_path: Path | None = None, extra: list[str] | None = None) -> list[str]:
    """Every string a MARKER_KV key or value can plausibly hash from: taxonomy keys and options,
    client names, mark ids and raw settings seen in marks.csv."""
    out = {"client-name", "mark-id", "", "true", "false"}
    if taxonomy_path is not None and Path(taxonomy_path).exists():
        tax = yaml.safe_load(Path(taxonomy_path).read_text(encoding="utf-8")) or {}
        out.update(str(c) for c in tax.get("clients") or [])
        out.update(str(c) for c in tax.get("client-names") or [])
        for spec in (tax.get("modules") or {}).values():
            for key, opts in ((spec or {}).get("settings") or {}).items():
                out.add(str(key))
                if isinstance(opts, list):
                    out.update(str(o).lower() for o in opts)
                    out.update(str(o) for o in opts)
    for s in extra or []:
        if s is None or (isinstance(s, float) and s != s):
            continue
        for pair in str(s).replace(";", " ").split():
            k, _, v = pair.partition("=")
            out.update({k, v})
    return sorted(out)


def build_lookup(strings: list[str]) -> dict[int, str]:
    return {hash_check_name(s): s for s in strings}


def decode_kv(markers: pd.DataFrame, lookup: dict[int, str]) -> pd.DataFrame:
    kv = markers[markers["kind"] == "kv"].copy() if "kind" in markers.columns else markers.iloc[0:0].copy()
    if kv.empty:
        return kv.assign(key_name=pd.Series(dtype=object), val_name=pd.Series(dtype=object))
    kv["key_name"] = kv["key_hash"].map(lambda h: lookup.get(_to_i32(h)))
    kv["val_name"] = kv["val_hash"].map(lambda h: lookup.get(_to_i32(h)))
    return kv


def attach_kv_names(windows: pd.DataFrame, markers: pd.DataFrame | None, taxonomy_path: Path | None,
                    marks_csv: Path | None = None) -> pd.DataFrame:
    """Adds mark_client_name / mark_kv to staff-marker windows from the latest mark start of that
    player session at or before the window start."""
    out = windows.copy()
    out["mark_client_name"] = out.get("mark_client_name", pd.Series([None] * len(out), index=out.index))
    out["mark_kv"] = out.get("mark_kv", pd.Series([None] * len(out), index=out.index))
    if markers is None or markers.empty or "kind" not in markers.columns or "label_source" not in out.columns:
        return out
    extra = []
    if marks_csv is not None and Path(marks_csv).exists():
        log = pd.read_csv(marks_csv)
        for col in ("settings", "client", "id", "marker_id"):
            if col in log.columns:
                extra.extend(log[col].tolist())
    if "mark_id" in out.columns:
        extra.extend(out["mark_id"].dropna().astype(str).tolist())
    kv = decode_kv(markers, build_lookup(candidate_strings(taxonomy_path, extra)))
    starts = markers[(markers["kind"] == "marker") & (pd.to_numeric(markers["action"], errors="coerce") == 1)]
    if kv.empty or starts.empty:
        return out
    meta = out[["window_key", "anon_player_id", "session_id"]].drop_duplicates("window_key")
    kv = kv.drop(columns=["wall_ms", "anon_player_id", "session_id"], errors="ignore")
    kv = kv.merge(starts[["window_key", "nanos", "wall_ms"]], on=["window_key", "nanos"], how="inner").merge(meta, on="window_key")
    if kv.empty:
        return out
    rows = []
    for (anon, sess, wall), g in kv.groupby(["anon_player_id", "session_id", "wall_ms"]):
        pairs = [f"{k}={v if v is not None else '?'}" for k, v in zip(g["key_name"], g["val_name"]) if k is not None]
        client = next((v for k, v in zip(g["key_name"], g["val_name"]) if k == "client-name"), None)
        rows.append({"anon_player_id": anon, "session_id": sess, "_wall": int(wall), "_client": client, "_kv": ";".join(pairs)})
    seg = pd.DataFrame(rows).sort_values("_wall")
    staff = out["label_source"] == "staff-marker"
    if not staff.any():
        return out
    w = out.loc[staff, ["anon_player_id", "session_id", "epoch_ms"]].copy()
    w["_idx"] = w.index
    w["_t"] = pd.to_numeric(w["epoch_ms"], errors="coerce").fillna(0).astype("int64")
    w = w.sort_values("_t")
    seg["_wall"] = seg["_wall"].astype("int64")
    hit = pd.merge_asof(w, seg, left_on="_t", right_on="_wall", by=["anon_player_id", "session_id"], direction="backward")
    hit = hit.set_index("_idx")
    out.loc[hit.index, "mark_client_name"] = hit["_client"]
    out.loc[hit.index, "mark_kv"] = hit["_kv"]
    return out
