from pathlib import Path

import pandas as pd
import pytest

from grimml_train.labels.join import is_negative, join_labels
from grimml_train.labels.segments import reconstruct_segments, apply_segments
from grimml_train.split.splits import (
    assert_no_leakage,
    assert_not_synthetic,
    grouped_split,
    leave_one_out,
    require_operator_for_cheat,
)


def _windows():
    return pd.DataFrame(
        [
            {"window_key": "w1", "anon_player_id": 1, "session_id": 9, "epoch_ms": 2000, "window_start_nanos": 0, "window_end_nanos": 2_000_000_000, "fight_id": 1},
            {"window_key": "w2", "anon_player_id": 1, "session_id": 9, "epoch_ms": 5000, "window_start_nanos": 0, "window_end_nanos": 2_000_000_000, "fight_id": 1},
            {"window_key": "w3", "anon_player_id": 2, "session_id": 8, "epoch_ms": 2000, "window_start_nanos": 0, "window_end_nanos": 2_000_000_000, "fight_id": 2},
        ]
    )


def _markers(interrupted=False):
    return pd.DataFrame(
        [
            {"window_key": "w1", "kind": "marker", "action": 1, "wall_ms": 1000, "nanos": 1, "label_id": 2, "client_id": 1, "module_id": 3, "interrupted": False},
            {"window_key": "w2", "kind": "marker", "action": 0, "wall_ms": 8000, "nanos": 2, "label_id": 2, "client_id": 1, "module_id": 3, "interrupted": interrupted},
        ]
    )


def test_unlabeled_is_not_negative():
    assert is_negative("UNLABELED") is False
    assert is_negative("LEGIT_CONFIRMED") is True
    assert is_negative("LEGIT_PROBABLE") is False
    assert is_negative("LEGIT_PROBABLE", allow_probable=True) is True


def test_trim_drops_segment_edges():
    segs = reconstruct_segments(_windows(), _markers(), trim_seconds=1.0, exclude_interrupted=False)
    assert segs.iloc[0]["from_ms"] == 2000
    assert segs.iloc[0]["to_ms"] == 7000


def test_interrupted_excluded():
    segs = reconstruct_segments(_windows(), _markers(interrupted=True), trim_seconds=0, exclude_interrupted=True)
    assert segs.empty


def test_join_priority_replay_over_weak(tmp_path: Path):
    windows = _windows()
    (tmp_path / "grim-labels.csv").write_text("kind,anon_id,label,source\nwindow,1,GRIM_WEAK,grim-weak\n")
    (tmp_path / "grim-review.csv").write_text("review_id,anon_id,status\n1,1,confirmed\n")
    # anon_id in sidecar is base36 of 1 -> "1"
    out = join_labels(windows, tmp_path, trim_seconds=0, markers=_markers(), exclude_interrupted=False)
    # replay-confirmed beats staff and grim-weak
    one = out[out["anon_player_id"] == 1]
    assert set(one["label_source"].unique()) <= {"replay-confirmed", "staff-marker"}


def test_weak_and_unlabeled_never_in_test():
    df = pd.DataFrame(
        {
            "anon_player_id": [1, 1, 2, 2, 3, 3],
            "session_id": [1, 1, 2, 2, 3, 3],
            "fight_id": [10, 10, 20, 20, 30, 30],
            "window_key": list("abcdef"),
            "label": ["CHEAT", "CHEAT", "LEGIT_CONFIRMED", "LEGIT_CONFIRMED", "GRIM_WEAK", "UNLABELED"],
            "label_source": ["staff-marker", "staff-marker", "staff-marker", "staff-marker", "grim-weak", "unlabeled"],
            "operator": ["op1", "op1", "op2", "op2", "op3", "op3"],
            "yaw_vel_mean": [1, 2, 3, 4, 5, 6],
        }
    )
    train, test = grouped_split(df, seed=0)
    assert not (test["label_source"] == "grim-weak").any()
    assert not (test["label"] == "UNLABELED").any()
    assert_no_leakage(train, test)


def test_synthetic_never_in_test():
    df = pd.DataFrame(
        {
            "source": ["synthetic", "real", "real", "real"],
            "anon_player_id": [9, 1, 2, 3],
            "session_id": [9, 1, 2, 3],
            "window_key": list("abcd"),
            "label": ["CHEAT", "CHEAT", "LEGIT_CONFIRMED", "LEGIT_CONFIRMED"],
            "label_source": ["staff-marker"] * 4,
            "fight_id": [9, 1, 2, 3],
        }
    )
    train, test = grouped_split(df, test_size=0.5, seed=0)
    assert_not_synthetic(test)
    assert not (test["source"] == "synthetic").any()


def test_operator_required_for_cheat():
    df = pd.DataFrame({"label": ["CHEAT"], "operator": [None]})
    with pytest.raises(ValueError, match="operator"):
        require_operator_for_cheat(df)


def test_session_cannot_span_split():
    train = pd.DataFrame({"session_id": [1], "anon_player_id": [1], "window_key": ["a"], "fight_id": [9]})
    test = pd.DataFrame({"session_id": [1], "anon_player_id": [2], "window_key": ["b"], "fight_id": [8]})
    with pytest.raises(ValueError, match="session_id"):
        assert_no_leakage(train, test)


def test_leave_one_client_and_operator():
    df = pd.DataFrame(
        {
            "anon_player_id": [1, 2, 3, 4],
            "session_id": [1, 2, 3, 4],
            "window_key": list("abcd"),
            "label": ["CHEAT", "LEGIT_CONFIRMED", "CHEAT", "LEGIT_CONFIRMED"],
            "label_source": ["staff-marker"] * 4,
            "mark_client_id": ["meteor", "meteor", "vape", "vape"],
            "operator": ["op1", "op1", "op2", "op2"],
        }
    )
    loco = leave_one_out(df, "mark_client_id")
    assert len(loco) == 2
    looo = leave_one_out(df, "operator")
    assert len(looo) == 2
