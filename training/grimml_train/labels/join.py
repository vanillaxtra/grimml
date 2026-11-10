from __future__ import annotations

from pathlib import Path

import pandas as pd

from grimml_train.labels.cheat_log import apply_cheat_log, load_cheat_log
from grimml_train.labels.segments import apply_segments, reconstruct_segments
from grimml_train.util import anon_id_base36

PRIORITY = {
    "replay-confirmed": 5,
    "staff-marker": 4,
    "ban-backfill": 3,
    "grim-auto-window": 2,
    "grim-auto-player": 2,
    "grim-weak": 2,
    "operator_asserted_bulk": 1,
    "unlabeled": 0,
}

NEGATIVES = {"LEGIT_CONFIRMED", "LEGIT_PROBABLE"}
POSITIVES = {"CHEAT"}
WEAK_SOURCES = {"grim-weak", "grim-auto-window", "grim-auto-player", "ban-backfill"}
TEST_FORBIDDEN_SOURCES = WEAK_SOURCES


def _read_csv(path: Path) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame()
    return pd.read_csv(path)


def join_labels(
    windows: pd.DataFrame,
    sidecar_dir: Path,
    trim_seconds: float = 0.5,
    markers: pd.DataFrame | None = None,
    exclude_interrupted: bool = True,
) -> pd.DataFrame:
    if windows.empty:
        return windows
    out = windows.copy()
    if "header_label_source" not in out.columns and "label_source" in out.columns:
        if out["label_source"].dtype != object:
            out.rename(columns={"label_source": "header_label_source"}, inplace=True)
    out["label"] = "UNLABELED"
    out["label_source"] = "unlabeled"
    out["label_priority"] = 0
    if "test_account" in out.columns:
        out["is_test_account"] = out["test_account"].eq(True)
    else:
        out["is_test_account"] = False

    # cheat/legit from trimmed marker segments only (no untrimmed local bypass)
    if markers is not None and not markers.empty:
        segs = reconstruct_segments(out, markers, trim_seconds, exclude_interrupted=exclude_interrupted)
        out = apply_segments(out, segs)
        staff = out["label_source"] == "staff-marker"
        out.loc[staff, "label_priority"] = PRIORITY["staff-marker"]

    out["_anon36"] = out["anon_player_id"].apply(lambda x: anon_id_base36(int(x)) if pd.notna(x) else "")

    def apply(source: str, label: str, mask: pd.Series, priority: int):
        nonlocal out
        pick = mask & (priority >= out["label_priority"])
        out.loc[pick, "label"] = label
        out.loc[pick, "label_source"] = source
        out.loc[pick, "label_priority"] = priority

    grim_review = _read_csv(sidecar_dir / "grim-review.csv")
    roster = _read_csv(sidecar_dir / "roster.csv")
    grim_labels = _read_csv(sidecar_dir / "grim-labels.csv")
    cheat_log = load_cheat_log(sidecar_dir / "cheat_log.csv")
    if cheat_log.empty:
        cheat_log = load_cheat_log(sidecar_dir / "marks.csv")
    out = apply_cheat_log(out, cheat_log)

    if not grim_review.empty and "status" in grim_review.columns:
        for _, row in grim_review.iterrows():
            anon = str(row.get("anon_id", ""))
            m = out["_anon36"] == anon
            if row.get("status") == "confirmed":
                apply("replay-confirmed", "CHEAT", m, PRIORITY["replay-confirmed"])
            elif row.get("status") == "legit":
                apply("replay-confirmed", "LEGIT_CONFIRMED", m, PRIORITY["replay-confirmed"])

    if not roster.empty:
        if "operator" in roster.columns:
            op_map = {str(r.anon_id): r.operator for r in roster.itertuples(index=False) if pd.notna(getattr(r, "operator", None))}
            out["operator"] = out["_anon36"].map(op_map)
        if "label" in roster.columns:
            for _, row in roster.iterrows():
                apply(
                    "ban-backfill",
                    str(row.get("label", "SUSPECT")),
                    out["_anon36"] == str(row.get("anon_id", "")),
                    PRIORITY["ban-backfill"],
                )

    if not grim_labels.empty and "label" in grim_labels.columns:
        for _, row in grim_labels.iterrows():
            src = str(row.get("source", "grim-weak"))
            apply(src, str(row.get("label", "GRIM_WEAK")), out["_anon36"] == str(row.get("anon_id", "")), PRIORITY.get(src, 2))

    out["confirmed_label"] = out["label"].isin(POSITIVES | {"LEGIT_CONFIRMED"})
    out.loc[out["label"] == "UNLABELED", "confirmed_label"] = False
    out.drop(columns=["_anon36"], inplace=True, errors="ignore")
    return out


def is_negative(label: str, allow_probable: bool = False) -> bool:
    if label == "UNLABELED":
        return False
    if label == "LEGIT_CONFIRMED":
        return True
    return allow_probable and label == "LEGIT_PROBABLE"
