"""Schema 4 cross-language test: the Java writer's sample window must decode to exactly what it wrote.

The fixture is written by recorder/.../BinaryFormatSelfCheck:
    java -Dgrimml.dump=training/tests/fixtures/schema4_sample.gmlml -cp recorder/target/classes dev.grimml.format.BinaryFormatSelfCheck
"""
from __future__ import annotations

from io import BytesIO
from pathlib import Path

import pytest

from grimml_train.decode import records as R
from grimml_train.decode.gml_common import read_ext
from grimml_train.decode.window import decode_file
from grimml_train.features.extract import features_from_decoded

SAMPLE = Path(__file__).parent / "fixtures" / "schema4_sample.gmlml"


@pytest.fixture(scope="module")
def decoded():
    return decode_file(SAMPLE)


def test_header(decoded):
    w = decoded["window"]
    assert w["schema_version"] == 4
    assert w["fight_id"] == 11 and w["window_id"] == 22
    assert w["client_protocol"] == 767
    assert w["taxonomy_version"] == 1 and w["taxonomy_hash"] == 0xABC


def test_schema3_fields_unchanged(decoded):
    f0, f1 = decoded["frames"]
    assert (f0["x_milli"], f0["y_milli"], f0["z_milli"], f0["yaw_centi"], f0["pitch_centi"]) == (1000, 2000, 3000, 4500, -1225)
    assert (f1["x_milli"], f1["z_milli"], f1["yaw_centi"], f1["pitch_centi"]) == (1010, 3005, 4600, -1200)


def test_frame_extension(decoded):
    f0, f1 = decoded["frames"]
    assert f0["yaw_exact"] == pytest.approx(45.0123, abs=1e-5)
    assert f0["pitch_exact"] == pytest.approx(-12.25)
    assert f0["client_tick"] == 77
    assert f0["opp_anon"] == 0x123456789ABC
    assert (f0["opp_dx_milli"], f0["opp_dy_milli"], f0["opp_dz_milli"]) == (-2500, 0, 1800)
    assert f0["opp_yaw_centi"] == 18000 and f0["opp_pose"] == 1
    assert f0["opp_state"] == R.OPP_ON_GROUND | R.OPP_BLOCKING
    assert f0["opp_block_ago_ms"] == 120 and f0["opp_hurt_ago_ms"] == -1
    assert f0["nearby_players"] == 2
    assert f0["max_gap_ms"] == 51 and f0["burst"] == 3
    # frame 1 had no exact rotation and no opponent
    assert f1["yaw_exact"] is None and f1["opp_anon"] is None
    assert f1["client_tick"] == 0


def test_event_extension(decoded):
    attack, marker = decoded["events"]
    assert attack["event_type"] == "attack" and attack["a"] == 55
    assert attack["client_tick"] == 78
    assert attack["g"] & 0xFF == 1 and attack["g"] >> 8 == 20  # player target, 20 cooldown ticks
    assert (attack["h"], attack["i"], attack["j"]) == (300_000, 1_500_000, -250_000)
    assert marker["event_type"] == "marker" and (marker["g"], marker["h"]) == (0, 0)
    m = decoded["markers"][0]
    assert m["label_id"] == 2 and m["module_id"] == 3


def test_model_features_still_work(decoded):
    feats = features_from_decoded(decoded)
    assert feats["frame_count"] == 2
    assert feats["yaw_vel_mean"] == 100.0
    assert feats["attack_count"] == 1


def test_unknown_tags_are_skipped():
    # tag 9 (unknown, 3 bytes) followed by a known client tick: decoders must step over what they do not know
    payload = bytes([9, 3, 1, 2, 3, R.EX_CLIENT_TICK, 1, 10])
    ext = read_ext(BytesIO(bytes([len(payload)]) + payload))
    assert set(ext) == {9, R.EX_CLIENT_TICK}
    assert ext[R.EX_CLIENT_TICK].read() == bytes([10])
