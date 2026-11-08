import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from grimml_train.config import TrainConfig
from grimml_train.decode.varint import hash_check_name
from grimml_train.eval.drift import drift_report, psi
from grimml_train.eval.report import evaluate
from grimml_train.export.onnx_export import export_bundle
from grimml_train.features.target import attack_geometry, compute_targets
from grimml_train.ingest import ingest, load_ingest_table
from grimml_train.labels.kv_decode import attach_kv_names
from grimml_train.labels.segments import apply_segments
from grimml_train.maps.gmm import line_of_sight, map_key, read_map
from grimml_train.split.cv import grouped_cv
from grimml_train.train.dual import family_model, multitask_model
from grimml_train.train.families import AIM_COLUMNS, MACRO_COLUMNS, add_family, family_of, module_names
from grimml_train.train.lgbm import threshold_on_val, train_fold
from tests.fixtures.encode_samples import encode_gml3_selfcheck
from tests.test_audit_train_eval import _toy

ROOT = Path(__file__).resolve().parents[2]
TAXONOMY = ROOT / "src" / "main" / "resources" / "taxonomy.yml"


def test_min_overlap_skips_edge_windows():
    w = pd.DataFrame([
        {"window_key": "inside", "anon_player_id": 1, "session_id": 1, "epoch_ms": 2000, "window_start_nanos": 0, "window_end_nanos": 2_000_000_000},
        {"window_key": "edge", "anon_player_id": 1, "session_id": 1, "epoch_ms": 9000, "window_start_nanos": 0, "window_end_nanos": 2_000_000_000},
    ])
    segs = pd.DataFrame([{"anon_player_id": 1, "session_id": 1, "from_ms": 1000, "to_ms": 10000, "label": "CHEAT",
                          "client_id": 1, "module_id": 1, "interrupted": False}])
    loose = apply_segments(w, segs, min_overlap=0.0).set_index("window_key")["label"]
    strict = apply_segments(w, segs, min_overlap=0.9).set_index("window_key")["label"]
    assert loose["edge"] == "CHEAT" and strict["edge"] == "UNLABELED" and strict["inside"] == "CHEAT"


def test_kv_hashes_decode_to_client_name():
    w = pd.DataFrame([{"window_key": "w", "anon_player_id": 1, "session_id": 1, "epoch_ms": 5000, "label_source": "staff-marker"}])
    m = pd.DataFrame([
        {"window_key": "w", "kind": "marker", "nanos": 10, "wall_ms": 4000, "action": 1, "key_hash": None, "val_hash": None},
        {"window_key": "w", "kind": "kv", "nanos": 10, "wall_ms": None, "action": None,
         "key_hash": hash_check_name("client-name"), "val_hash": hash_check_name("meteor")},
        {"window_key": "w", "kind": "kv", "nanos": 10, "wall_ms": None, "action": None,
         "key_hash": hash_check_name("strength"), "val_hash": hash_check_name("high")},
    ])
    out = attach_kv_names(w, m, TAXONOMY)
    assert out.loc[0, "mark_client_name"] == "meteor"
    assert "strength=high" in out.loc[0, "mark_kv"]


def test_module_families_follow_yaml_order():
    names = module_names(TAXONOMY)
    assert names[1] == "aim_assist" and family_of(names[1]) == "aim"
    assert family_of("auto_totem") == "macro" and family_of("unknown") == "other"


def test_aim_geometry_straight_on():
    att = {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "sneak": False}
    tgt = {"x": 0.0, "y": 64.72, "z": 3.0, "yaw": 0.0, "pitch": 0.0, "sneak": False}
    g = attack_geometry(att, tgt)
    assert g["aim_err_deg"] == pytest.approx(0.0, abs=1e-6)
    assert g["reach"] == pytest.approx(2.7, abs=1e-6)
    assert not g["ray_miss"]
    side = attack_geometry({**att, "yaw": 90.0}, tgt)
    assert side["ray_miss"] and side["aim_err_deg"] == pytest.approx(90.0, abs=1e-6)


def test_compute_targets_joins_by_boot_anon_tick():
    tracks = pd.DataFrame([
        {"boot_id": 7, "anon_player_id": 1, "tick": 10, "x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "sneak": False},
        {"boot_id": 7, "anon_player_id": 2, "tick": 10, "x": 0.0, "y": 64.72, "z": 3.0, "yaw": 0.0, "pitch": 0.0, "sneak": False},
    ])
    attacks = pd.DataFrame([{"window_key": "w", "boot_id": 7, "anon_player_id": 1, "target_anon": 2, "tick": 10, "map_key": "x"}])
    t = compute_targets(tracks, attacks).set_index("window_key")
    assert t.loc["w", "reach_mean"] == pytest.approx(2.7, abs=1e-6)
    assert t.loc["w", "ray_miss_rate"] == 0.0


def test_java_map_snapshot_line_of_sight():
    f = ROOT / "target" / "selfcheck" / (map_key("map1", "arena") + ".gmlmap")
    if not f.exists():
        pytest.skip("run mvn package first")
    snap = read_map(f)
    assert snap.palette[0] == "grimml:unloaded" and set(snap.palette[1:]) == {"minecraft:air", "minecraft:stone"}
    assert snap.size == (4, 4, 4) and snap.origin == (0, 64, 0)
    assert line_of_sight(snap, (0.5, 65.5, 0.5), (3.5, 65.5, 0.5)) is False
    assert line_of_sight(snap, (0.5, 65.5, 0.5), (0.5, 65.5, 3.5)) is True
    assert line_of_sight(snap, (0.5, 65.5, 0.5), (0.5, 65.5, 9.5)) is None


def test_psi_flags_shift_only():
    rng = np.random.default_rng(0)
    a = rng.normal(size=2000)
    assert psi(a, rng.normal(size=2000)) < 0.1
    assert psi(a, rng.normal(loc=2.0, size=2000)) > 0.2
    rep = drift_report(pd.DataFrame({"cps": a}), pd.DataFrame({"cps": a + 3}), ["cps", "missing"])
    assert rep["flagged"] == ["cps"]


def test_eval_breakdowns_cv_dual_multitask():
    df = _toy(80)
    df["is_bedrock"] = [i % 3 == 0 for i in range(len(df))]
    df["ping_ms"] = [20 + (i % 4) * 70 for i in range(len(df))]
    df["mark_module_id"] = [1 if i % 4 == 0 else 4 for i in range(len(df))]
    clf, scaler, cols = train_fold(df, seed=0)
    rep = evaluate(clf, scaler, cols, df, 0.5)
    assert set(rep["breakdowns"]["bedrock"]) == {"bedrock", "java"}
    assert set(rep["breakdowns"]["ping_bucket"]) >= {"<50", "200+"}
    cv = grouped_cv(df, 4, 0, lambda tr: train_fold(tr, 0),
                    lambda m, val, te: evaluate(m[0], m[1], m[2], te, threshold_on_val(m[0], m[1], val, m[2], 0.05)))
    tested = [f for f in cv["folds"] if "skipped" not in f]
    assert len(tested) >= 2 and "recall" in cv["summary"]
    fam = add_family(df, module_names(TAXONOMY))
    assert set(fam["family"].dropna()) == {"legit", "aim", "macro"}
    dual = family_model(fam, "aim", 0)
    assert "auc" in dual or "skipped" in dual
    mt = multitask_model(fam, 0, sorted(set(AIM_COLUMNS) | set(MACRO_COLUMNS)))
    assert "macro_f1" in mt or "skipped" in mt


def test_export_onnx_parity_on_every_row(tmp_path: Path):
    pytest.importorskip("onnxmltools")
    df = _toy(80)
    df.loc[df.index[::5], "cps"] = np.nan
    clf, scaler, cols = train_fold(df, seed=0)
    rep = export_bundle(clf, scaler, cols, df, tmp_path, 0.5)
    assert rep["onnx"] is True and rep["rows"] == len(df)
    assert json.loads((tmp_path / "model_meta.json").read_text())["onnx"] is True


def test_export_rejects_offline_only_features(tmp_path: Path):
    df = _toy(40)
    clf, scaler, cols = train_fold(df, seed=0)
    with pytest.raises(ValueError):
        export_bundle(clf, scaler, cols + ["reach_mean"], df.assign(reach_mean=1.0), tmp_path, 0.5)


def test_ingest_keeps_frames_and_geometry(tmp_path: Path):
    dump = tmp_path / "dump"
    dump.mkdir()
    (dump / "a.gmlml").write_bytes(encode_gml3_selfcheck())
    cfg = TrainConfig(recordings_dir=dump, sidecar_dir=dump, out_dir=tmp_path / "out", workers=1, keep_frames=True)
    ingest(cfg)
    assert len(load_ingest_table(cfg.out_dir, "frames")) == 2
    assert len(load_ingest_table(cfg.out_dir, "tracks")) == 2
    assert len(load_ingest_table(cfg.out_dir, "attacks")) == 1
