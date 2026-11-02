from __future__ import annotations

import struct
import zlib
from io import BytesIO

from grimml_train.decode import records as R
from grimml_train.decode.varint import hash_check_name


def _write_str255(out: BytesIO, s: str) -> None:
    b = s.encode("utf-8")[:255]
    out.write(bytes([len(b)]))
    out.write(b)


def _write_var(out: BytesIO, v: int) -> None:
    u = (v << 1) ^ (v >> 31)
    while (u & ~0x7F) != 0:
        out.write(bytes([(u & 0x7F) | 0x80]))
        u >>= 7
    out.write(bytes([u & 0x7F]))


def _frame_tail(out: BytesIO) -> None:
    out.write(struct.pack(">i", 0))
    out.write(bytes([0, 0, 0]))
    out.write(struct.pack(">i", 0))
    out.write(bytes([0]))
    out.write(struct.pack(">i", 0))
    out.write(bytes([0]))
    out.write(struct.pack(">hh", 0, 0))
    out.write(struct.pack(">iiiii", 0, 0, 0, 0, 0))


def _build_gml23_records() -> bytes:
    b = BytesIO()
    b.write(struct.pack(">H", 2))
    specs = [(100, 1000, 2000, 3000, 4500, -1225), (101, 1010, 2000, 3005, 4600, -1200)]
    ox = oy = oz = oyaw = opitch = 0
    for i, (tick, x, y, z, yaw, pitch) in enumerate(specs):
        b.write(bytes([R.FRAME]))
        b.write(struct.pack(">iibH", tick, 0, 0, 0))
        if i == 0:
            b.write(struct.pack(">iiiii", x, y, z, yaw, pitch))
        else:
            for d in (x - ox, y - oy, z - oz, yaw - oyaw, pitch - opitch):
                _write_var(b, d)
        ox, oy, oz, oyaw, opitch = x, y, z, yaw, pitch
        _frame_tail(b)
    b.write(struct.pack(">H", 2))
    b.write(bytes([R.ATTACK]))
    b.write(struct.pack(">iiB", 101, 0, 0))
    b.write(struct.pack(">q", 1_160_000))
    b.write(struct.pack(">q", 55))
    b.write(struct.pack(">iiiii", 0, 0, 0, 0, 0))
    b.write(struct.pack(">H", 0))
    b.write(bytes([R.MARKER]))
    b.write(struct.pack(">iiB", 101, 0, 0))
    b.write(struct.pack(">q", 1_160_100))
    b.write(struct.pack(">q", 1_700_000_000_000))
    packed = R.MARKER_START | (1 << 8) | (1 << 16)
    b.write(struct.pack(">i", packed))
    b.write(struct.pack(">iiii", 2, 1, 3, hash_check_name("strength=high")))
    b.write(struct.pack(">H", 0))
    return b.getvalue()


def _zlib_wrap(plain: bytes) -> bytes:
    return zlib.compress(plain, level=1)


def encode_gml3_selfcheck() -> bytes:
    compressed = _zlib_wrap(_build_gml23_records())
    out = BytesIO()
    out.write(b"GML3")
    out.write(bytes([3]))
    out.write(struct.pack(">q", 11))
    out.write(struct.pack(">q", 22))
    out.write(bytes([1, 1, 0]))
    out.write(struct.pack(">H", 0))
    out.write(struct.pack(">q", 123456789))
    out.write(struct.pack(">q", 987654321))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">q", 1_700_000_000_000))
    out.write(struct.pack(">q", 1_000_000))
    out.write(struct.pack(">q", 2_000_000))
    out.write(struct.pack(">q", 99))
    out.write(struct.pack(">ii", 0, 0))
    out.write(struct.pack(">hhhhhh", 0, 0, 0, 42, 0, 767))
    out.write(struct.pack(">i", 16))
    out.write(bytes([0, 0, 0]))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">hh", 0, 0))
    out.write(bytes([0]))
    for _ in range(10):
        out.write(struct.pack(">i", 0))
    for s in ("vanilla", "ffa", "mace", "", "", "1.21", "", ""):
        _write_str255(out, s)
    out.write(struct.pack(">i", 1))
    out.write(struct.pack(">q", 0xABC))
    out.write(struct.pack(">i", len(compressed)))
    out.write(compressed)
    return out.getvalue()


def encode_gml1_minimal() -> bytes:
    rec = BytesIO()
    rec.write(bytes([R.ROTATION]))
    rec.write(struct.pack(">i", 10))
    rec.write(struct.pack(">ff", 90.0, 0.0))
    rec.write(struct.pack(">q", 1000))
    rec.write(bytes([R.ATTACK]))
    rec.write(struct.pack(">i", 11))
    rec.write(struct.pack(">q", 2000))
    rec.write(struct.pack(">ii", 42, 0))
    rec.write(struct.pack(">H", 50))
    compressed = _zlib_wrap(rec.getvalue())
    out = BytesIO()
    out.write(b"GML1")
    out.write(bytes([1]))
    out.write(struct.pack(">q", 1))
    out.write(struct.pack(">q", 2))
    out.write(struct.pack(">q", 100))
    out.write(struct.pack(">q", 200))
    out.write(struct.pack(">HHH", 0, 50, 767))
    out.write(struct.pack(">H", 0))
    out.write(struct.pack(">i", len(compressed)))
    out.write(compressed)
    return out.getvalue()


def encode_gml2_minimal() -> bytes:
    compressed = _zlib_wrap(_build_gml23_records())
    out = BytesIO()
    out.write(b"GML2")
    out.write(bytes([2]))
    out.write(struct.pack(">q", 1))
    out.write(struct.pack(">q", 2))
    out.write(bytes([1, 1, 0]))
    out.write(struct.pack(">H", 0))
    out.write(struct.pack(">q", 10))
    out.write(struct.pack(">q", 20))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">q", 0))
    out.write(struct.pack(">q", 100))
    out.write(struct.pack(">q", 200))
    out.write(struct.pack(">q", 0))
    out.write(struct.pack(">ii", 0, 0))
    out.write(struct.pack(">hhhhhh", 0, 0, 0, 0, 0, 0))
    out.write(struct.pack(">i", 0))
    out.write(bytes([0, 0, 0]))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">i", 0))
    out.write(struct.pack(">hh", 0, 0))
    out.write(bytes([0]))
    for _ in range(10):
        out.write(struct.pack(">i", 0))
    for s in ("", "srv", "kit", "", "map", "", "", ""):
        _write_str255(out, s)
    out.write(struct.pack(">i", len(compressed)))
    out.write(compressed)
    return out.getvalue()
