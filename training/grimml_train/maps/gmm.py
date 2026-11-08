"""Reader for plugin map snapshots (plugins/grimml/maps/<map-id>__<world>.gmlmap, magic GMM1).

Layout (big endian): magic, u8 version, u16-len str map_id / world / server_id, i64 epoch ms,
i32 min x/y/z, i32 size x/y/z, i32 palette size, palette (u16-len block data strings, index 0 is
grimml:unloaded), i32 compressed length, zlib(varint palette index per block, y then z then x).
"""
from __future__ import annotations

import math
import re
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np

UNLOADED = "grimml:unloaded"
# shapes the attack ray goes through; everything else counts as blocking. heuristic, not collision exact
_PASSABLE = re.compile(
    r"^minecraft:(air|cave_air|void_air|water|lava|light|short_grass|grass|tall_grass|fern|large_fern|dead_bush|"
    r"snow\[layers=1\]|.*_sapling|.*_flower|dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|"
    r"cornflower|lily_of_the_valley|torchflower|.*torch|.*_button|lever|.*_sign|.*_wall_sign|.*_hanging_sign|"
    r"redstone_wire|rail|.*_rail|tripwire|tripwire_hook|string|.*_carpet|vine|.*_vines|sugar_cane|.*_mushroom|"
    r"nether_sprouts|.*_roots|seagrass|tall_seagrass|kelp|kelp_plant|structure_void|cobweb|.*_pressure_plate)"
    r"(\[.*\])?$"
)


@dataclass
class MapSnapshot:
    map_id: str
    world: str
    server_id: str
    epoch_ms: int
    origin: tuple[int, int, int]
    size: tuple[int, int, int]
    palette: list[str]
    blocks: np.ndarray  # (size_y, size_z, size_x) palette indices

    def solid_lut(self) -> np.ndarray:
        return np.array([p != UNLOADED and not _PASSABLE.match(p) for p in self.palette], dtype=bool)

    def known_lut(self) -> np.ndarray:
        return np.array([p != UNLOADED for p in self.palette], dtype=bool)

    def block_at(self, x: float, y: float, z: float) -> int | None:
        ix = math.floor(x) - self.origin[0]
        iy = math.floor(y) - self.origin[1]
        iz = math.floor(z) - self.origin[2]
        sx, sy, sz = self.size
        if not (0 <= ix < sx and 0 <= iy < sy and 0 <= iz < sz):
            return None
        return int(self.blocks[iy, iz, ix])


def _str(buf: memoryview, off: int) -> tuple[str, int]:
    (n,) = struct.unpack_from(">H", buf, off)
    off += 2
    return bytes(buf[off : off + n]).decode("utf-8"), off + n


def read_map(path: Path) -> MapSnapshot:
    raw = Path(path).read_bytes()
    if raw[:4] != b"GMM1":
        raise ValueError(f"not a gmlmap file: {path}")
    buf = memoryview(raw)
    off = 5
    map_id, off = _str(buf, off)
    world, off = _str(buf, off)
    server, off = _str(buf, off)
    (epoch,) = struct.unpack_from(">q", buf, off)
    off += 8
    mx, my, mz, sx, sy, sz, pal_n = struct.unpack_from(">7i", buf, off)
    off += 28
    palette = []
    for _ in range(pal_n):
        s, off = _str(buf, off)
        palette.append(s)
    (clen,) = struct.unpack_from(">i", buf, off)
    off += 4
    body = zlib.decompress(bytes(buf[off : off + clen]))
    idx = _read_varints(body, sx * sy * sz)
    return MapSnapshot(map_id, world, server, epoch, (mx, my, mz), (sx, sy, sz), palette, idx.reshape(sy, sz, sx))


def _read_varints(body: bytes, n: int) -> np.ndarray:
    arr = np.frombuffer(body, dtype=np.uint8)
    if arr.size == n and not (arr & 0x80).any():
        return arr.astype(np.int32)
    out = np.empty(n, dtype=np.int32)
    i = 0
    k = 0
    while k < n:
        v = 0
        shift = 0
        while True:
            b = body[i]
            i += 1
            v |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        out[k] = v
        k += 1
    return out


def map_key(map_id: str | None, world: str | None) -> str:
    def clean(s):
        if not s:
            return "unknown"
        return "".join(c if (c.isascii() and (c.isalnum() or c in "-_")) else "_" for c in s.lower())

    return f"{clean(map_id)}__{clean(world)}"


def load_maps(maps_dir: Path | None) -> dict[str, MapSnapshot]:
    if maps_dir is None or not Path(maps_dir).is_dir():
        return {}
    return {p.stem: read_map(p) for p in Path(maps_dir).glob("*.gmlmap")}


def line_of_sight(snap: MapSnapshot, p0, p1, step: float = 0.05) -> bool | None:
    """True if no blocking block between p0 and p1 (endpoints excluded), None if the ray leaves the
    snapshot or crosses an unloaded chunk."""
    solid = snap.solid_lut()
    known = snap.known_lut()
    d = np.asarray(p1, dtype=float) - np.asarray(p0, dtype=float)
    dist = float(np.linalg.norm(d))
    if dist <= step:
        return True
    n = int(dist / step)
    last = None
    for i in range(1, n):
        p = np.asarray(p0, dtype=float) + d * (i / n)
        cell = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
        if cell == last:
            continue
        last = cell
        b = snap.block_at(*p)
        if b is None or not known[b]:
            return None
        if solid[b]:
            return False
    return True
