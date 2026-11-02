from pathlib import Path
import struct
import zlib

import pytest

from grimml_train.decode import records as R
from grimml_train.decode.gml_common import UnknownRecordTypeError
from grimml_train.decode.window import decode_file
from tests.fixtures.encode_samples import encode_gml1_minimal, encode_gml2_minimal, encode_gml3_selfcheck


def test_gml1_missing_fields_are_null(tmp_path: Path):
    p = tmp_path / "a.gmlml"
    p.write_bytes(encode_gml1_minimal())
    w = decode_file(p)["window"]
    f = decode_file(p)["frames"][0]
    assert w["schema_version"] == 1
    assert w["taxonomy_hash"] is None
    assert w["boot_id"] is None
    assert w["valid_partial"] is None
    assert w["valid_teleport"] is None
    assert f["x_milli"] is None
    assert f["ack_tick"] is None
    assert f["x_milli"] != 0


def test_gml2_taxonomy_null_gml3_present(tmp_path: Path):
    p2 = tmp_path / "b.gmlml"
    p2.write_bytes(encode_gml2_minimal())
    w2 = decode_file(p2)["window"]
    assert w2["schema_version"] == 2
    assert w2["taxonomy_version"] is None
    p3 = tmp_path / "c.gmlml"
    p3.write_bytes(encode_gml3_selfcheck())
    w3 = decode_file(p3)["window"]
    assert w3["schema_version"] == 3
    assert w3["taxonomy_version"] == 1
    assert w3["taxonomy_hash"] == 0xABC
    assert w3["fight_id"] == 11
    assert w3["window_id"] == 22
    assert w3["boot_id"] == 99
    assert w3["ping_ms"] == 42


def test_validity_flags_kept(tmp_path: Path):
    from tests.fixtures.encode_samples import encode_gml3_with_validity

    p = tmp_path / "v.gmlml"
    bits = R.VALID_LAG | R.VALID_TELEPORT | R.VALID_SETBACK
    p.write_bytes(encode_gml3_with_validity(bits))
    w = decode_file(p)["window"]
    assert w["valid_lag"] is True
    assert w["valid_teleport"] is True
    assert w["valid_setback"] is True
    assert w["valid_death"] is False
    assert w["valid_vanish"] is False


def test_unknown_record_type_raises(tmp_path: Path):
    rec = bytearray()
    rec += bytes([99])
    rec += struct.pack(">i", 1)
    rec += struct.pack(">q", 2)
    compressed = zlib.compress(bytes(rec), level=1)
    out = bytearray()
    out += b"GML1"
    out += bytes([1])
    out += struct.pack(">qqqq", 1, 2, 100, 200)
    out += struct.pack(">HHH", 0, 0, 0)
    out += struct.pack(">H", 0)
    out += struct.pack(">i", len(compressed))
    out += compressed
    p = tmp_path / "bad.gmlml"
    p.write_bytes(out)
    with pytest.raises(UnknownRecordTypeError):
        decode_file(p)
