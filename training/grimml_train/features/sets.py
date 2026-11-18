"""Which feature columns a module trains on.

Three sets are always built:
  all       every usable feature (including window length, ping and counts)
  core      what the published model uses: no ping/length/count features, so it cannot learn the recording setup
  shortcut  ONLY window length, ping and counts. If this set separates cheat from legit, the labels leak through
            the setup and the core number cannot be trusted.
"""
from __future__ import annotations

import pandas as pd

from grimml_train.features.extract import FEATURE_COLUMNS
from grimml_train.features.schema4 import SCHEMA4_FEATURES

SHORTCUT_COLS = ["win_len_ms", "frame_count", "event_count", "attack_count", "ping_ms", "jitter_ms"]
CORE_DROP = {"ping_ms", "jitter_ms", "frame_count", "attack_count", "win_len_ms"}
# Schema 4 features that measure the connection, not the player (they differ between recording days and servers,
# like ping_ms). Never in core; they join the shortcut set instead.
S4_NETWORK = ["bt_rtt_mean", "bt_rtt_std", "bt_pong_burst_frac", "bt_gap_p95_ms", "bt_burst_mean"]


def feature_sets(d: pd.DataFrame, *, schema4: bool = False, families: tuple[str, ...] = ()) -> dict[str, list[str]]:
    """Feature sets for the windows in d. With schema4, the module's own schema 4 families are added (the detector
    computes these live too, checked against Python on every build)."""
    base = [c for c in FEATURE_COLUMNS if c in d.columns]
    if schema4:
        base += [c for c in SCHEMA4_FEATURES if c in d.columns and c not in S4_NETWORK
                 and (not families or c.startswith(tuple(families)))]
    if "win_len_ms" in d.columns and "win_len_ms" not in base:
        base.append("win_len_ms")
    usable = [c for c in base if d[c].notna().any()]
    core = [c for c in usable if c not in CORE_DROP]
    short = [c for c in SHORTCUT_COLS + (S4_NETWORK if schema4 else []) if c in d.columns and d[c].notna().any()]
    return {"all": usable, "core": core, "shortcut": short or usable[:1]}
