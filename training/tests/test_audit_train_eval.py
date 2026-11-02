import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from grimml_train.eval.report import confound_probe, fp_per_1000_player_hours, player_aggregate, warn_confound_importance
from grimml_train.features.extract import FEATURE_COLUMNS, features_from_decoded
from grimml_train.features.forbidden import assert_no_forbidden_features
from grimml_train.train.lgbm import fit_scaler, threshold_on_val, train_fold, transform


def _toy(n=40, seed=0):
    rng = np.random.default_rng(seed)
    rows = []
    for i in range(n):
        cheat = i % 2 == 0
        rows.append(
            {
                "window_key": str(i),
                "anon_player_id": i // 4,
                "session_id": i // 4,
                "fight_id": i // 4,
                "label": "CHEAT" if cheat else "LEGIT_CONFIRMED",
                "label_source": "staff-marker",
                "operator": f"op{i // 8}",
                "yaw_vel_mean": 10 + (8 if cheat else 0) + rng.normal(),
                "yaw_vel_std": 1.0,
                "yaw_vel_abs_mean": 10,
                "yaw_jerk_mean": 1,
                "yaw_snap_rate": 0.2 if cheat else 0.01,
                "gcd_grid": 10,
                "pitch_vel_mean": 0,
                "pitch_range": 1,
                "speed_mean": 1,
                "on_ground_frac": 0.5,
                "attack_count": 3,
                "cps": 8 if cheat else 4,
                "click_gap_mean_ms": 50,
                "click_gap_cv": 0.1,
                "ping_ms": 40,
                "jitter_ms": 5,
                "frame_count": 40,
                "source": "real",
            }
        )
    return pd.DataFrame(rows)


def test_forbidden_identity_columns():
    with pytest.raises(AssertionError, match="forbidden"):
        assert_no_forbidden_features(["yaw_vel_mean", "anon_player_id"])
    with pytest.raises(AssertionError, match="forbidden"):
        assert_no_forbidden_features(["grim_flag_count"])
    assert_no_forbidden_features(["yaw_vel_mean", "cps"])


def test_features_ignore_markers():
    decoded = {
        "window": {"schema_version": 3},
        "frames": [{"yaw_centi": 0, "pitch_centi": 0, "x_milli": 0, "z_milli": 0, "flags": 1, "ack_tick": 3}],
        "events": [{"event_type": "marker", "nanos": 1}, {"event_type": "attack", "nanos": 1e6}],
    }
    f = features_from_decoded(decoded)
    assert "label" not in f
    assert f["timing_ack_compensated"] is True
    assert f["event_count"] == 1


def test_gml1_nan_not_zero():
    decoded = {"window": {"schema_version": 1}, "frames": [{"yaw_centi": 100, "pitch_centi": 0}], "events": []}
    f = features_from_decoded(decoded)
    assert f["speed_mean"] != 0 or np.isnan(f["speed_mean"])
    assert np.isnan(f["speed_mean"])
    assert np.isnan(f["on_ground_frac"])
    assert f["gml1_xyz_nan"] is True


def test_scaler_fit_on_train_only():
    train = np.array([[10.0, 1.0], [20.0, 1.0]])
    test = np.array([[100.0, 1.0]])
    scaler = fit_scaler(train)
    tr = transform(scaler, train)
    te = transform(scaler, test)
    assert abs(tr[:, 0].mean()) < 1e-9
    assert te[0, 0] > 5


def test_threshold_then_single_test_apply():
    df = _toy()
    train = df[df["anon_player_id"] < 6]
    val = df[(df["anon_player_id"] >= 6) & (df["anon_player_id"] < 8)]
    test = df[df["anon_player_id"] >= 8]
    clf, scaler, cols = train_fold(train, seed=0)
    thr = threshold_on_val(clf, scaler, val, cols, max_fpr=0.5)
    x, y, _ = __import__("grimml_train.train.lgbm", fromlist=["_xy"])._xy(test, cols)
    proba = clf.predict_proba(transform(scaler, x))[:, 1]
    pred = (proba >= thr).astype(int)
    assert pred.shape == y.shape


def test_fp_per_1000_hours_primary():
    y = np.array([0, 0, 0, 1])
    pred = np.array([1, 0, 0, 1])
    hours = np.array([1.0, 1.0, 1.0, 1.0])
    v = fp_per_1000_player_hours(y, pred, hours)
    assert v == pytest.approx(1000 / 3)


def test_player_aggregation():
    out = player_aggregate(np.array([0.9, 0.9, 0.1]), np.array(["a", "a", "b"]), n_alert=3)
    assert out["players"] == 2


def test_confound_probe_warns_when_separable():
    n = 40
    df = pd.DataFrame(
        {
            "yaw_vel_mean": np.r_[np.zeros(n // 2), np.ones(n // 2) * 10],
            "cps": 1.0,
            "is_test_account": np.r_[np.zeros(n // 2, dtype=int), np.ones(n // 2, dtype=int)],
        }
    )
    auc = confound_probe(df, ["yaw_vel_mean", "cps"], "is_test_account")
    assert auc > 0.6


def test_export_json_has_order_scaler_threshold(tmp_path: Path):
    from grimml_train.export.onnx_export import export_bundle

    df = _toy()
    clf, scaler, cols = train_fold(df, seed=0)
    export_bundle(clf, scaler, cols, df, tmp_path, 0.4)
    meta = json.loads((tmp_path / "model_meta.json").read_text())
    assert meta["feature_names"] == cols
    assert meta["threshold"] == 0.4
    assert len(meta["scaler_mean"]) == len(cols)
    assert (tmp_path / "model.txt").exists()


def test_run_meta_written(tmp_path: Path):
    from grimml_train.config import TrainConfig
    from grimml_train.run import write_run_meta

    cfg = TrainConfig(out_dir=tmp_path, recordings_dir=tmp_path)
    p = write_run_meta(cfg, {"stage": "test"})
    data = json.loads(p.read_text())
    assert data["seed"] == 42
    assert "git_commit" in data
    assert "data_manifest_hash" in data
    assert "config" in data
