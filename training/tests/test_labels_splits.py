import pandas as pd

from grimml_train.labels.join import join_labels
from grimml_train.split.splits import assert_not_synthetic, grouped_train_test_split


def test_unlabeled_never_legit(tmp_path):
    windows = pd.DataFrame(
        [{"window_key": "a", "anon_player_id": 99, "session_id": 1, "test_account": False}]
    )
    out = join_labels(windows, tmp_path)
    assert out.iloc[0]["label"] == "UNLABELED"


def test_synthetic_guard():
    df = pd.DataFrame({"source": ["real", "synthetic"]})
    try:
        assert_not_synthetic(df.iloc[[1]])
        assert False
    except ValueError:
        pass


def test_grouped_split():
    df = pd.DataFrame(
        {
            "window_key": ["a", "b", "c", "d"],
            "anon_player_id": [1, 1, 2, 2],
            "label": ["CHEAT", "LEGIT_CONFIRMED", "CHEAT", "LEGIT_CONFIRMED"],
            "confirmed_label": [True, True, True, True],
        }
    )
    tr, te = grouped_train_test_split(df, "anon_player_id", test_size=0.5, seed=0)
    assert len(tr) + len(te) == 4
