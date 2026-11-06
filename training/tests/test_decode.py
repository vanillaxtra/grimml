from pathlib import Path

import pytest

from grimml_train.decode.varint import TruncatedFileError
from grimml_train.decode.window import decode_file, parse_filename, schema_of
from tests.fixtures.encode_samples import encode_gml1_minimal, encode_gml2_minimal, encode_gml3_selfcheck


def test_schema_of():
    assert schema_of(b"GML1") == 1
    assert schema_of(b"GML2") == 2
    assert schema_of(b"GML3") == 3


def test_decode_gml3_roundtrip(tmp_path: Path):
    path = tmp_path / "sample.gmlml"
    path.write_bytes(encode_gml3_selfcheck())
    d = decode_file(path)
    w = d["window"]
    assert w["schema_version"] == 3
    assert w["fight_id"] == 11
    assert w["anon_player_id"] == 987654321
    assert len(d["frames"]) == 2
    assert d["frames"][1]["yaw_centi"] == 4600
    markers = [m for m in d["markers"] if m.get("kind") == "marker"]
    assert len(markers) == 1
    assert markers[0]["wall_ms"] == 1_700_000_000_000


def test_decode_gml1_gml2(tmp_path: Path):
    p1 = tmp_path / "a.gmlml"
    p1.write_bytes(encode_gml1_minimal())
    d1 = decode_file(p1)
    assert d1["window"]["schema_version"] == 1
    assert len(d1["frames"]) == 1
    assert d1["frames"][0]["x_milli"] is None
    assert d1["events"][0]["event_type"] == "attack"

    p2 = tmp_path / "b.gmlml"
    p2.write_bytes(encode_gml2_minimal())
    d2 = decode_file(p2)
    assert d2["window"]["schema_version"] == 2
    assert d2["window"]["taxonomy_version"] is None
    assert len(d2["frames"]) == 2


def test_unknown_record_raises(tmp_path: Path):
    pytest.skip("covered by test_audit_decoder.test_unknown_record_type_raises")


def test_truncated_file(tmp_path: Path):
    p = tmp_path / "short.gmlml"
    p.write_bytes(b"GML3\x03\x00\x01")
    with pytest.raises(TruncatedFileError):
        decode_file(p)


def test_parse_filename():
    a = parse_filename("2026-11-02_m83e5404d_a3ac2e82.gmlml")
    assert a["file_date"] == "2026-11-02"
    assert a["mark_id"] == "m83e5404d"
    b = parse_filename("2026-10-29_107bblr5q9esn_2s7poz5g3ocvu_192778eb.gmlml")
    assert b["file_date"] == "2026-10-29"
    assert b["mark_id"] is None


def test_gml3_roundtrip_file_date(tmp_path: Path):
    path = tmp_path / "2026-11-02_m83e5404d_deadbeef.gmlml"
    path.write_bytes(encode_gml3_selfcheck())
    w = decode_file(path)["window"]
    assert w["file_date"] == "2026-11-02"
    assert w["mark_id"] == "m83e5404d"
