from pathlib import Path

import pandas as pd

from grimml_train.config import BulkLabel, TrainConfig
from grimml_train.ingest import is_recent, parse_shard_range, path_meta
from grimml_train.labels.bulk import apply_bulk, undo_bulk, write_labels_table
from grimml_train.labels.join import PRIORITY, join_labels
from grimml_train.split.sample import build_sample, load_or_write_holdout


def test_priority_order():
    assert PRIORITY["replay-confirmed"] > PRIORITY["staff-marker"] > PRIORITY["ban-backfill"]
    assert PRIORITY["ban-backfill"] > PRIORITY["grim-weak"] > PRIORITY["operator_asserted_bulk"] > PRIORITY["unlabeled"]


def test_bulk_overridden_by_marker_and_skips_cheat_dir(tmp_path: Path):
    cheat = tmp_path / "cheat"
    live = tmp_path / "live"
    cheat.mkdir()
    live.mkdir()
    windows = pd.DataFrame(
        [
            {"window_key": "a", "window_id": 1, "anon_player_id": 1, "session_id": 1, "epoch_ms": 1000, "window_start_nanos": 0, "window_end_nanos": 1e9, "path": str(live / "a.gmlml"), "label": "UNLABELED", "label_source": "unlabeled", "label_priority": 0},
            {"window_key": "b", "window_id": 2, "anon_player_id": 2, "session_id": 2, "epoch_ms": 1000, "window_start_nanos": 0, "window_end_nanos": 1e9, "path": str(live / "b.gmlml"), "label": "CHEAT", "label_source": "staff-marker", "label_priority": PRIORITY["staff-marker"]},
            {"window_key": "c", "window_id": 3, "anon_player_id": 3, "session_id": 3, "epoch_ms": 1000, "window_start_nanos": 0, "window_end_nanos": 1e9, "path": str(cheat / "c.gmlml"), "label": "UNLABELED", "label_source": "unlabeled", "label_priority": 0},
        ]
    )
    cfg = TrainConfig(
        out_dir=tmp_path / "out",
        cheat_dir=cheat,
        bulk_labels=[BulkLabel(path=tmp_path, label="LEGIT_CONFIRMED", source="operator_asserted_bulk", asserted_by="operator", note="n")],
    )
    out = apply_bulk(windows, cfg)
    assert out.loc[out.window_key == "a", "label"].iloc[0] == "LEGIT_CONFIRMED"
    assert out.loc[out.window_key == "a", "label_source"].iloc[0] == "operator_asserted_bulk"
    assert out.loc[out.window_key == "b", "label"].iloc[0] == "CHEAT"
    assert out.loc[out.window_key == "c", "label"].iloc[0] == "UNLABELED"


def test_replay_overrides_bulk(tmp_path: Path):
    windows = pd.DataFrame(
        [{"window_key": "w1", "anon_player_id": 1, "session_id": 9, "epoch_ms": 2000, "window_start_nanos": 0, "window_end_nanos": 2e9, "path": str(tmp_path / "w1.gmlml")}]
    )
    (tmp_path / "grim-review.csv").write_text("review_id,anon_id,status\n1,1,confirmed\n")
    joined = join_labels(windows, tmp_path, trim_seconds=0, markers=None)
    cfg = TrainConfig(out_dir=tmp_path / "out", bulk_labels=[BulkLabel(path=tmp_path)])
    out = apply_bulk(joined, cfg)
    assert out.loc[0, "label"] == "CHEAT"
    assert out.loc[0, "label_source"] == "replay-confirmed"


def test_operator_account_routing(tmp_path: Path):
    windows = pd.DataFrame(
        [
            {"window_key": "c", "anon_player_id": 7, "session_id": 1, "path": str(tmp_path / "c.gmlml"), "label": "CHEAT", "label_source": "staff-marker", "label_priority": 4},
            {"window_key": "l", "anon_player_id": 7, "session_id": 2, "path": str(tmp_path / "l.gmlml"), "label": "UNLABELED", "label_source": "unlabeled", "label_priority": 0},
            {"window_key": "x", "anon_player_id": 8, "session_id": 3, "path": str(tmp_path / "x.gmlml"), "label": "UNLABELED", "label_source": "unlabeled", "label_priority": 0},
        ]
    )
    cfg = TrainConfig(out_dir=tmp_path / "out", bulk_labels=[BulkLabel(path=tmp_path)])
    out = apply_bulk(windows, cfg)
    op = out[out.anon_player_id == 7]
    assert op["operator_account"].all()
    assert set(op["pair_group"]) == {"operator"}
    live = out[out.anon_player_id == 8].iloc[0]
    assert live["operator_account"] == False
    assert live["pair_group"] == "live"
    assert live["label"] == "LEGIT_CONFIRMED"


def test_undo_bulk(tmp_path: Path):
    windows = pd.DataFrame(
        [{"window_key": "a", "window_id": 1, "anon_player_id": 1, "path": str(tmp_path / "a.gmlml"), "label": "UNLABELED", "label_source": "unlabeled", "label_priority": 0}]
    )
    cfg = TrainConfig(out_dir=tmp_path / "out", bulk_labels=[BulkLabel(path=tmp_path)])
    labeled = apply_bulk(windows, cfg)
    write_labels_table(labeled, cfg.out_dir)
    n = undo_bulk(cfg.out_dir)
    assert n == 1
    lab = pd.read_parquet(cfg.out_dir / "labels" / "labels.parquet")
    assert not (lab["label_source"] == "operator_asserted_bulk").any()


def test_skip_recent_minutes(tmp_path: Path):
    f = tmp_path / "a.gmlml"
    f.write_bytes(b"GML3")
    assert is_recent(f, 10) is True
    assert is_recent(f, 0) is False


def test_path_meta_server_tag(tmp_path: Path):
    p = tmp_path / "srv" / "x.gmlml"
    p.parent.mkdir()
    p.write_bytes(b"x")
    tag, folder = path_meta(p, tmp_path)
    assert tag == "srv"
    assert folder == "srv"


def test_errors_dont_stop_batch(tmp_path: Path):
    from grimml_train.ingest import ingest
    from tests.fixtures.encode_samples import encode_gml3_selfcheck

    rec = tmp_path / "rec"
    rec.mkdir()
    (rec / "ok.gmlml").write_bytes(encode_gml3_selfcheck())
    (rec / "bad.gmlml").write_bytes(b"GML3not-a-window")
    (rec / "ok2.gmlml").write_bytes(encode_gml3_selfcheck())
    cfg = TrainConfig(recordings_dir=rec, out_dir=tmp_path / "out", shard_size=10, workers=1, skip_recent_minutes=0)
    s = ingest(cfg)
    assert s["ok"] == 2
    assert s["errors"] == 1
    err = (cfg.out_dir / "ingest" / "errors.csv").read_text(encoding="utf-8")
    assert "unknown_type" in err or "truncated" in err or "corrupt" in err
    done = (cfg.out_dir / "ingest" / "done.txt").read_text(encoding="utf-8")
    assert "bad.gmlml" in done
    s2 = ingest(cfg)
    assert s2["ok"] == 0
    assert s2["already_done"] >= 3


def test_shard_range_and_resume(tmp_path: Path):
    assert parse_shard_range("1:3") == (1, 3)
    assert parse_shard_range(None) == (0, None)
    from grimml_train.ingest import ingest
    from tests.fixtures.encode_samples import encode_gml3_selfcheck

    rec = tmp_path / "rec"
    rec.mkdir()
    for i in range(4):
        (rec / f"{i}.gmlml").write_bytes(encode_gml3_selfcheck())
    cfg = TrainConfig(recordings_dir=rec, out_dir=tmp_path / "out", shard_size=10, workers=1, skip_recent_minutes=0)
    ingest(cfg, max_files=2)
    done = (cfg.out_dir / "ingest" / "done.txt").read_text(encoding="utf-8").splitlines()
    assert len(done) == 2
    ingest(cfg, max_files=10)
    done2 = (cfg.out_dir / "ingest" / "done.txt").read_text(encoding="utf-8").splitlines()
    assert len(done2) == 4


def test_per_account_cap_and_holdout_seed(tmp_path: Path):
    rows = []
    for acct in range(10):
        for i in range(20):
            rows.append(
                {
                    "window_key": f"{acct}-{i}",
                    "anon_player_id": acct,
                    "schema_version": 3,
                    "server_tag": "s",
                    "file_date": "2026-11-01",
                    "label": "LEGIT_CONFIRMED",
                    "operator_account": False,
                    "pair_group": "live",
                    "window_start_nanos": 0,
                    "window_end_nanos": 1e9,
                }
            )
    w = pd.DataFrame(rows)
    cfg = TrainConfig(out_dir=tmp_path / "out", seed=42, per_account_cap=5, sample_target=40)
    a = load_or_write_holdout(w["anon_player_id"].to_numpy(), cfg.out_dir, 42, False)
    b = load_or_write_holdout(w["anon_player_id"].to_numpy(), cfg.out_dir, 99, False)
    assert a == b
    s = build_sample(w, cfg, regenerate_holdout=False)
    assert s["all_versions"] <= 40
    cap = pd.read_parquet(cfg.out_dir / "samples" / "live_legit_v1.parquet")
    assert cap.groupby("anon_player_id").size().max() <= 5
    assert not set(cap["anon_player_id"].astype(str)) & set(a)
