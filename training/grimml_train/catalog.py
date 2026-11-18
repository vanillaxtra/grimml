"""The labelled sessions (config/sessions.csv) and the rules on top of them (config/labels.yaml)."""
from __future__ import annotations

import csv
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd
import yaml

from grimml_train.modules import ConfigError
from grimml_train.paths import LABELS_YAML, SESSIONS_CSV

CHEAT = "CHEAT"
LEGIT = "LEGIT_CONFIRMED"
LABELS = (CHEAT, LEGIT)

COLUMNS = ["mark_id", "label", "client", "module", "settings", "start_ms", "end_ms", "interrupted", "multi_module", "note"]


@dataclass(frozen=True)
class LabelRules:
    exclude: dict[str, str] = field(default_factory=dict)     # mark id -> reason
    same_fight: dict[str, str] = field(default_factory=dict)  # mark id -> group mark id
    validity_fixed_from_ms: int | None = None

    @classmethod
    def load(cls, path: Path = LABELS_YAML) -> LabelRules:
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        exclude = {str(m).lower(): reason for reason, ids in (raw.get("exclude") or {}).items() for m in ids}
        same = {str(m).lower(): str(g).lower() for g, ids in (raw.get("same_fight") or {}).items() for m in ids}
        return cls(exclude, same, parse_time_ms(raw.get("validity_fixed_from")))

    def fight_group(self, mark_id: str) -> str:
        return self.same_fight.get(mark_id, mark_id)


def parse_time_ms(value) -> int | None:
    """UTC epoch ms from an int, a digit string or an ISO time; None stays None."""
    if value is None or str(value).strip() == "":
        return None
    s = str(value).strip()
    if s.isdigit():
        return int(s)
    return int(pd.to_datetime(s, utc=True).timestamp() * 1000)


def read_sessions(path: Path = SESSIONS_CSV) -> pd.DataFrame:
    """The session table as written, without any exclusion applied. Lines starting with # are comments."""
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(line for line in f if line.strip() and not line.lstrip().startswith("#")))
    t = pd.DataFrame(rows, columns=COLUMNS).fillna("")
    t["mark_id"] = t["mark_id"].str.strip().str.lower()
    dupes = t.loc[t["mark_id"].duplicated(), "mark_id"].tolist()
    if dupes:
        raise ConfigError(f"{path.name}: mark ids listed twice: {dupes}")
    bad = t.loc[~t["label"].isin(LABELS), "mark_id"].tolist()
    if bad:
        raise ConfigError(f"{path.name}: label must be {' or '.join(LABELS)} (rows {bad})")
    for c in ("start_ms", "end_ms"):
        t[c] = pd.to_numeric(t[c]).astype("int64")
    for c in ("interrupted", "multi_module"):
        t[c] = t[c].str.strip().str.lower().isin(("true", "yes", "1"))
    t["secs"] = (t["end_ms"] - t["start_ms"]) / 1000.0
    return t


def session_table(rules: LabelRules, *, min_secs: float, include_interrupted_legit: bool = False,
                  legit_ids: set[str] | None = None, drop_ids: set[str] | None = None,
                  path: Path = SESSIONS_CSV) -> pd.DataFrame:
    """Sessions with an `exclude_reason` column ("" = usable). The first reason that applies wins."""
    t = read_sessions(path)

    def reason(r) -> str:
        if r.label == CHEAT:
            if r.multi_module:
                return "two modules were on at once"
            if r.interrupted:
                return "segment was interrupted"
            if r.module == "UNLOGGED":
                return "module not in your log"
            if r.secs < min_secs:
                return f"shorter than {min_secs:.0f}s"
        elif r.interrupted and not include_interrupted_legit:
            return "segment was interrupted"
        return ""

    t["exclude_reason"] = t.apply(reason, axis=1) if len(t) else ""
    free = t["exclude_reason"] == ""
    if legit_ids:
        t.loc[free & (t["label"] == LEGIT) & ~t["mark_id"].isin(legit_ids), "exclude_reason"] = "not in --legit-ids"
    excluded = t["mark_id"].map(rules.exclude).fillna("")
    t.loc[(t["exclude_reason"] == "") & (excluded != ""), "exclude_reason"] = excluded
    if drop_ids:
        t.loc[(t["exclude_reason"] == "") & t["mark_id"].isin(drop_ids), "exclude_reason"] = "in --drop-ids"
    return t
