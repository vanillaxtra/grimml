from __future__ import annotations

import pandas as pd

from grimml_train.decode.records import MARKER_END, MARKER_INTERRUPTED, MARKER_START

LABEL_NAMES = {1: "LEGIT_CONFIRMED", 2: "CHEAT"}


def _label_name(label_id) -> str:
    if pd.isna(label_id):
        return "UNLABELED"
    return LABEL_NAMES.get(int(label_id), "UNLABELED")


def reconstruct_segments(
    windows: pd.DataFrame,
    markers: pd.DataFrame,
    trim_seconds: float = 0.5,
    exclude_interrupted: bool = True,
) -> pd.DataFrame:
    if windows.empty or markers.empty:
        return pd.DataFrame(
            columns=["anon_player_id", "session_id", "from_ms", "to_ms", "label", "client_id", "module_id", "interrupted"]
        )
    mk = markers[markers.get("kind", "marker") == "marker"].copy() if "kind" in markers.columns else markers.copy()
    if mk.empty:
        return pd.DataFrame(
            columns=["anon_player_id", "session_id", "from_ms", "to_ms", "label", "client_id", "module_id", "interrupted"]
        )
    meta = windows[["window_key", "anon_player_id", "session_id", "epoch_ms", "window_end_nanos", "window_start_nanos"]].drop_duplicates("window_key")
    mk = mk.merge(meta, on="window_key", how="left")
    mk["wall_ms"] = pd.to_numeric(mk.get("wall_ms"), errors="coerce")
    mk = mk.dropna(subset=["anon_player_id", "session_id", "wall_ms"])
    mk = mk.sort_values(["anon_player_id", "session_id", "wall_ms", "nanos"], kind="mergesort")

    trim_ms = int(trim_seconds * 1000)
    rows = []
    open_seg = None
    last_key = None
    for r in mk.itertuples(index=False):
        key = (r.anon_player_id, r.session_id)
        action = int(r.action) if pd.notna(r.action) else -1
        interrupted = bool(getattr(r, "interrupted", False))
        if last_key is not None and key != last_key and open_seg is not None:
            open_seg["to_ms"] = open_seg["from_ms"] + 3_600_000
            open_seg["interrupted"] = True
            rows.append(open_seg)
            open_seg = None
        last_key = key
        if action == MARKER_START or (action == MARKER_END and open_seg is None and not interrupted):
            if action == MARKER_START:
                if open_seg is not None:
                    open_seg["to_ms"] = int(r.wall_ms)
                    open_seg["interrupted"] = True
                    rows.append(open_seg)
                open_seg = {
                    "anon_player_id": r.anon_player_id,
                    "session_id": r.session_id,
                    "from_ms": int(r.wall_ms),
                    "to_ms": None,
                    "label": _label_name(getattr(r, "label_id", None)),
                    "client_id": getattr(r, "client_id", None),
                    "module_id": getattr(r, "module_id", None),
                    "interrupted": False,
                }
        elif action in (MARKER_END, MARKER_INTERRUPTED) or interrupted:
            if open_seg is None:
                continue
            open_seg["to_ms"] = int(r.wall_ms)
            open_seg["interrupted"] = interrupted or action == MARKER_INTERRUPTED
            rows.append(open_seg)
            open_seg = None
    if open_seg is not None:
        open_seg["to_ms"] = open_seg["from_ms"] + 3_600_000
        open_seg["interrupted"] = True
        rows.append(open_seg)

    segs = pd.DataFrame(rows)
    if segs.empty:
        return segs
    segs["from_ms"] = segs["from_ms"] + trim_ms
    segs["to_ms"] = segs["to_ms"] - trim_ms
    segs = segs[segs["to_ms"] > segs["from_ms"]]
    segs = segs[segs["label"] != "UNLABELED"]
    if exclude_interrupted and "interrupted" in segs.columns:
        segs = segs[~segs["interrupted"].astype(bool)]
    return segs.reset_index(drop=True)


def apply_local_markers(windows: pd.DataFrame, markers: pd.DataFrame) -> pd.DataFrame:
    out = windows.copy()
    if "label" not in out.columns:
        out["label"] = "UNLABELED"
    if "label_source" not in out.columns:
        out["label_source"] = "unlabeled"
    if markers is None or markers.empty or "window_key" not in markers.columns:
        return out
    mk = markers.copy()
    if "kind" in mk.columns:
        mk = mk[mk["kind"] == "marker"]
    if mk.empty:
        return out
    mk = mk[pd.to_numeric(mk.get("label_id"), errors="coerce").notna()]
    mk["label"] = mk["label_id"].map(_label_name)
    mk = mk[mk["label"] != "UNLABELED"]
    if mk.empty:
        return out
    first = mk.drop_duplicates("window_key", keep="first")
    mapped = out["window_key"].map(first.set_index("window_key")["label"])
    hit = mapped.notna()
    out.loc[hit, "label"] = mapped[hit]
    out.loc[hit, "label_source"] = "staff-marker"
    if "client_id" in first.columns:
        out.loc[hit, "mark_client_id"] = out.loc[hit, "window_key"].map(first.set_index("window_key")["client_id"])
    if "module_id" in first.columns:
        out.loc[hit, "mark_module_id"] = out.loc[hit, "window_key"].map(first.set_index("window_key")["module_id"])
    return out


def apply_segments(windows: pd.DataFrame, segs: pd.DataFrame) -> pd.DataFrame:
    out = windows.copy()
    if "label" not in out.columns:
        out["label"] = "UNLABELED"
    if "label_source" not in out.columns:
        out["label_source"] = "unlabeled"
    if segs.empty:
        return out
    start = pd.to_numeric(out.get("epoch_ms"), errors="coerce")
    dur_ms = (
        pd.to_numeric(out.get("window_end_nanos"), errors="coerce")
        - pd.to_numeric(out.get("window_start_nanos"), errors="coerce")
    ) / 1e6
    end = start + dur_ms.fillna(7000)
    out["_ws"] = start
    out["_we"] = end
    for s in segs.itertuples(index=False):
        m = (
            (out["anon_player_id"] == s.anon_player_id)
            & (out["session_id"] == s.session_id)
            & (out["_we"] >= s.from_ms)
            & (out["_ws"] <= s.to_ms)
        )
        out.loc[m, "label"] = s.label
        out.loc[m, "label_source"] = "staff-marker"
        out.loc[m, "mark_client_id"] = s.client_id
        out.loc[m, "mark_module_id"] = s.module_id
        out.loc[m, "mark_interrupted"] = s.interrupted
    out.drop(columns=["_ws", "_we"], inplace=True, errors="ignore")
    return out
