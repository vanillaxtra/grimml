from __future__ import annotations

import struct
from io import BytesIO


class TruncatedFileError(ValueError):
    pass


def _read(stream: BytesIO, n: int) -> bytes:
    b = stream.read(n)
    if len(b) != n:
        raise TruncatedFileError(f"need {n} bytes got {len(b)}")
    return b


def read_u8(stream: BytesIO) -> int:
    return _read(stream, 1)[0]


def read_i32(stream: BytesIO) -> int:
    return struct.unpack(">i", _read(stream, 4))[0]


def read_i64(stream: BytesIO) -> int:
    return struct.unpack(">q", _read(stream, 8))[0]


def read_u64(stream: BytesIO) -> int:
    return struct.unpack(">Q", _read(stream, 8))[0]


def read_i16(stream: BytesIO) -> int:
    return struct.unpack(">h", _read(stream, 2))[0]


def read_u16(stream: BytesIO) -> int:
    return struct.unpack(">H", _read(stream, 2))[0]


def read_f32(stream: BytesIO) -> float:
    return struct.unpack(">f", _read(stream, 4))[0]


def read_str255(stream: BytesIO) -> str:
    n = read_u8(stream)
    if n == 0:
        return ""
    return _read(stream, n).decode("utf-8", errors="replace")


def read_varint(stream: BytesIO) -> int:
    u = 0
    shift = 0
    while True:
        b = read_u8(stream)
        u |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return (u >> 1) ^ -(u & 1)


def hash_check_name(name: str) -> int:
    h = 0x811C9DC5
    for ch in name:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h if h < 0x80000000 else h - 0x100000000
