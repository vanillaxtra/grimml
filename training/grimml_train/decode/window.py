from __future__ import annotations

import re
import struct
import zlib
from io import BytesIO
from pathlib import Path

from grimml_train.decode import records as R
from grimml_train.decode.gml_common import (
    UnknownRecordTypeError,
    read_events_v2,
    read_frames_v2,
    read_gml1_body,
)
from grimml_train.decode.varint import (
    TruncatedFileError,
    read_i32,
    read_i64,
    read_i16,
    read_u16,
    read_u8,
    read_str255,
    read_u64,
)

_FILE_DATE = re.compile(r"^(\d{4}-\d{2}-\d{2})_")
_MARK_ID = re.compile(r"_(m[0-9a-fA-F]{8})_")


def schema_of(data: bytes) -> int:
    if len(data) < 4 or data[0:3] != b"GML":
        return 0
    return data[3] - 48


def parse_filename(name: str) -> dict:
    date = None
    m = _FILE_DATE.match(name)
    if m:
        date = m.group(1)
    mark = None
    m2 = _MARK_ID.search(name)
    if m2:
        mark = m2.group(1).lower()
    return {"file_date": date, "mark_id": mark}


def decode_file(path: Path) -> dict:
    path = Path(path)
    try:
        raw = path.read_bytes()
    except OSError as e:
        raise TruncatedFileError(f"unreadable {path}: {e}") from e
    if len(raw) < 5 or raw[0:3] != b"GML":
        raise ValueError(f"not a gmlml file: {path}")
    schema = raw[3] - 48
    window_key = str(path.resolve())
    meta = parse_filename(path.name)
    stream = BytesIO(raw)
    stream.read(4)
    try:
        if schema == 1:
            out = _decode_gml1(stream, window_key, path.name)
        elif schema in (2, 3):
            out = _decode_gml23(stream, window_key, schema, path.name)
        else:
            raise ValueError(f"unsupported schema {schema} in {path}")
    except UnknownRecordTypeError:
        raise
    except (TruncatedFileError, EOFError, zlib.error, struct.error) as e:
        raise TruncatedFileError(f"truncated {path}: {e}") from e
    out["window"]["file_date"] = meta["file_date"]
    out["window"]["mark_id"] = meta["mark_id"]
    out["window"]["path"] = window_key
    return out


def _inflate(stream: BytesIO) -> BytesIO:
    clen = read_i32(stream)
    if clen < 0 or clen > 16 * 1024 * 1024:
        raise TruncatedFileError(f"compressed_len {clen}")
    blob = stream.read(clen)
    if len(blob) != clen:
        raise TruncatedFileError(f"compressed need {clen} got {len(blob)}")
    # java DeflaterOutputStream uses zlib wrapper
    return BytesIO(zlib.decompress(blob))


def _decode_gml1(stream: BytesIO, window_key: str, file_name: str) -> dict:
    _schema_byte = read_u8(stream)
    session_id = read_u64(stream)
    anon_id = read_u64(stream)
    start_nanos = read_u64(stream)
    end_nanos = read_u64(stream)
    state_flags = read_u16(stream)
    ping_ms = read_u16(stream)
    client_protocol = read_u16(stream)
    brand_len = read_u16(stream)
    brand = stream.read(brand_len).decode("utf-8", errors="replace") if brand_len else ""
    rec = _inflate(stream)
    frames, events, markers = read_gml1_body(rec, window_key)
    win = {
        "window_key": window_key,
        "file_name": file_name,
        "schema_version": 1,
        "fight_id": None,
        "window_id": None,
        "role": None,
        "trigger": None,
        "label_source": None,
        "validity": None,
        "session_id": session_id,
        "anon_player_id": anon_id,
        "entity_id": None,
        "epoch_ms": None,
        "window_start_nanos": start_nanos,
        "window_end_nanos": end_nanos,
        "boot_id": None,
        "tick_start": None,
        "tick_end": None,
        "mspt_start_centi": None,
        "mspt_end_centi": None,
        "tps_centi": None,
        "ping_ms": ping_ms,
        "jitter_ms": None,
        "client_protocol": client_protocol,
        "state_flags": state_flags,
        "main_hand": None,
        "gamemode": None,
        "test_account": None,
        "health_milli": None,
        "client_brand": brand,
        "server_id": None,
        "kit": None,
        "world": None,
        "map_id": None,
        "mc_version": None,
        "grim_version": None,
        "pe_version": None,
        "taxonomy_version": None,
        "taxonomy_hash": None,
        "is_bedrock": bool(state_flags & R.FLAG_BEDROCK),
        "is_staff": None,
        "file_date": None,
        "mark_id": None,
    }
    win.update({name: None for name, _bit in R.VALID_FLAGS})
    return {"window": win, "frames": frames, "events": events, "markers": markers}


def _decode_gml23(stream: BytesIO, window_key: str, schema: int, file_name: str) -> dict:
    _schema_byte = read_u8(stream)
    fight_id = read_u64(stream)
    window_id = read_u64(stream)
    role = read_u8(stream)
    trigger = read_u8(stream)
    label_source = read_u8(stream)
    validity = read_u16(stream)
    session_id = read_u64(stream)
    anon_id = read_u64(stream)
    entity_id = read_i32(stream)
    epoch_ms = read_u64(stream)
    start_nanos = read_u64(stream)
    end_nanos = read_u64(stream)
    boot_id = read_u64(stream)
    tick_start = read_i32(stream)
    tick_end = read_i32(stream)
    mspt_start = read_i16(stream)
    mspt_end = read_i16(stream)
    tps = read_u16(stream)
    ping_ms = read_u16(stream)
    jitter_ms = read_u16(stream)
    client_protocol = read_u16(stream)
    state_flags = read_i32(stream)
    main_hand = read_u8(stream)
    gamemode = read_u8(stream)
    test_account = read_u8(stream) == 1
    health_milli = read_i32(stream)
    _absorption = read_i32(stream)
    _food = read_u16(stream)
    _totem_count = read_u16(stream)
    _hotbar_totem = read_u8(stream)
    for _ in range(10):
        read_i32(stream)
    client_brand = read_str255(stream)
    server_id = read_str255(stream)
    kit = read_str255(stream)
    world = read_str255(stream)
    map_id = read_str255(stream)
    mc_version = read_str255(stream)
    grim_version = read_str255(stream)
    pe_version = read_str255(stream)
    tax_ver = None
    tax_hash = None
    if schema >= 3:
        tax_ver = read_i32(stream)
        tax_hash = read_u64(stream)
    rec = _inflate(stream)
    frames = read_frames_v2(rec, window_key, schema)
    events, markers = read_events_v2(rec, window_key, schema)
    win = {
        "window_key": window_key,
        "file_name": file_name,
        "schema_version": schema,
        "fight_id": fight_id,
        "window_id": window_id,
        "role": role,
        "trigger": trigger,
        "label_source": label_source,
        "validity": validity,
        "session_id": session_id,
        "anon_player_id": anon_id,
        "entity_id": entity_id,
        "epoch_ms": epoch_ms,
        "window_start_nanos": start_nanos,
        "window_end_nanos": end_nanos,
        "boot_id": boot_id,
        "tick_start": tick_start,
        "tick_end": tick_end,
        "mspt_start_centi": mspt_start,
        "mspt_end_centi": mspt_end,
        "tps_centi": tps,
        "ping_ms": ping_ms,
        "jitter_ms": jitter_ms,
        "client_protocol": client_protocol,
        "state_flags": state_flags,
        "main_hand": main_hand,
        "gamemode": gamemode,
        "test_account": test_account,
        "health_milli": health_milli,
        "client_brand": client_brand,
        "server_id": server_id,
        "kit": kit,
        "world": world,
        "map_id": map_id,
        "mc_version": mc_version,
        "grim_version": grim_version,
        "pe_version": pe_version,
        "taxonomy_version": tax_ver,
        "taxonomy_hash": tax_hash,
        "is_bedrock": bool(state_flags & R.FLAG_BEDROCK),
        "is_staff": bool(state_flags & R.FLAG_STAFF),
        "file_date": None,
        "mark_id": None,
    }
    for name, bit in R.VALID_FLAGS:
        win[name] = bool(validity & bit)
    return {"window": win, "frames": frames, "events": events, "markers": markers}
