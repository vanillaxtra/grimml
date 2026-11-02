from pathlib import Path

import pandas as pd

from grimml_train.decode.window import decode_file
from grimml_train.features.extract import features_from_decoded
from grimml_train.ingest import ingest, iter_gmlml
from grimml_train.labels.join import join_labels
from grimml_train.labels.segments import apply_segments, reconstruct_segments
from grimml_train.config import TrainConfig
from tests.fixtures.encode_samples import encode_gml3_selfcheck, encode_gml1_minimal


def test_iter_mixed_folder(tmp_path: Path):
    (tmp_path / "a.gmlml").write_bytes(encode_gml3_selfcheck())
    (tmp_path / "nested").mkdir()
    (tmp_path / "nested" / "b.gmlml").write_bytes(encode_gml1_minimal())
    (tmp_path / "readme.txt").write_text("no")
    files = list(iter_gmlml(tmp_path))
    assert len(files) == 2


def test_features_from_file(tmp_path: Path):
    p = tmp_path / "s.gmlml"
    p.write_bytes(encode_gml3_selfcheck())
    d = decode_file(p)
    f = features_from_decoded(d)
    assert f["frame_count"] == 2
    assert f["attack_count"] == 1
    assert f["has_xyz"] is True


def test_segments_cheat_and_legit():
    windows = pd.DataFrame(
        [
            {
                "window_key": "w1",
                "anon_player_id": 1,
                "session_id": 9,
                "epoch_ms": 1000,
                "window_start_nanos": 0,
                "window_end_nanos": 2_000_000_000,
            },
            {
                "window_key": "w2",
                "anon_player_id": 1,
                "session_id": 9,
                "epoch_ms": 5000,
                "window_start_nanos": 0,
                "window_end_nanos": 2_000_000_000,
            },
            {
                "window_key": "w3",
                "anon_player_id": 2,
                "session_id": 9,
                "epoch_ms": 1000,
                "window_start_nanos": 0,
                "window_end_nanos": 2_000_000_000,
            },
        ]
    )
    markers = pd.DataFrame(
        [
            {
                "window_key": "w1",
                "kind": "marker",
                "action": 1,
                "wall_ms": 1000,
                "nanos": 1,
                "label_id": 2,
                "client_id": 1,
                "module_id": 3,
                "interrupted": False,
            },
            {
                "window_key": "w2",
                "kind": "marker",
                "action": 0,
                "wall_ms": 8000,
                "nanos": 2,
                "label_id": 2,
                "client_id": 1,
                "module_id": 3,
                "interrupted": False,
            },
        ]
    )
    segs = reconstruct_segments(windows, markers, trim_seconds=0)
    labeled = apply_segments(windows, segs)
    assert labeled.loc[labeled["window_key"] == "w1", "label"].iloc[0] == "CHEAT"
    assert labeled.loc[labeled["window_key"] == "w2", "label"].iloc[0] == "CHEAT"
    assert labeled.loc[labeled["window_key"] == "w3", "label"].iloc[0] == "UNLABELED"


def test_join_unlabeled_stays(tmp_path: Path):
    windows = pd.DataFrame(
        [{"window_key": "a", "anon_player_id": 99, "session_id": 1, "test_account": False, "epoch_ms": 0, "window_start_nanos": 0, "window_end_nanos": 1}]
    )
    out = join_labels(windows, tmp_path, markers=pd.DataFrame())
    assert out.iloc[0]["label"] == "UNLABELED"


def test_ingest_mixed_dump(tmp_path: Path):
    dump = tmp_path / "dump"
    dump.mkdir()
    (dump / "one.gmlml").write_bytes(encode_gml3_selfcheck())
    (dump / "two.gmlml").write_bytes(encode_gml1_minimal())
    out = tmp_path / "out"
    cfg = TrainConfig(recordings_dir=dump, sidecar_dir=dump, out_dir=out, workers=1, shard_size=10, resume=False)
    summary = ingest(cfg)
    assert summary["ok"] == 2
    from grimml_train.ingest import load_ingest_table

    w = load_ingest_table(out, "windows")
    assert len(w) == 2
    labeled = join_labels(w, dump, markers=load_ingest_table(out, "markers"))
    cheat = labeled[labeled["label"] == "CHEAT"]
    assert len(cheat) >= 1
