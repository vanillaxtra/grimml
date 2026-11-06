# GML format notes (from plugin Java, not guessed)

Derived from `src/main/java/dev/grimml/format/BinaryFormat.java` (GML3, current), git `ed14351` (GML2), git `fa64b2d` `record/BinaryFormat.java` (GML1), `RecordTypes.java`, `FrameRing.java`, capture/mark writers, `BinaryFormatSelfCheck.java`.

Endianness is Java `DataOutputStream` = **big-endian**. Body after the header is **zlib** (`DeflaterOutputStream`, RFC1950 wrapper — Python `zlib.decompress`). One file = one combat window.

## Magic

| bytes | schema |
|---|---|
| `GML1` | 1 |
| `GML2` | 2 |
| `GML3` | 3 |

Immediately after the 4-byte magic: `writeByte(SCHEMA)` so GML3 files start `47 4d 4c 33 03`.

The current plugin **only encodes GML3**. Older files on disk are not rewritten. `E:\recordings` (sampled 2026-10-29) is **GML1**.

## Header

### GML1 (`fa64b2d`)

After magic + schema byte:

| field | type |
|---|---|
| session_id | i64 |
| anon_player_id | i64 |
| window_start_nanos | i64 |
| window_end_nanos | i64 |
| state_flags | u16 |
| ping_ms | u16 |
| client_protocol | u16 |
| brand_len | u16 (max 512) |
| brand | utf-8 bytes |
| compressed_len | i32 |
| compressed | zlib |

No fight/window ids, no epoch ms, no positions, no taxonomy, no MARKER events.

### GML2 (`ed14351`) / GML3 (current)

After magic + schema byte, same layout. GML3 adds taxonomy after the version strings.

| field | type |
|---|---|
| fight_id | i64 |
| window_id | i64 |
| role | u8 (1 attacker, 2 defender, 3 bystander) |
| trigger | u8 |
| label_source | u8 |
| validity | u16 |
| session_id | i64 |
| anon_player_id | i64 |
| entity_id | i32 |
| window_start_epoch_ms | i64 |
| window_start_nanos | i64 |
| window_end_nanos | i64 |
| boot_id | i64 |
| tick_start / tick_end | i32 |
| mspt_start_centi / mspt_end_centi | i16 |
| tps_centi | u16 |
| ping_ms / jitter_ms / client_protocol | u16 |
| state_flags | i32 |
| main_hand | u8 (0 right, 1 left) |
| gamemode | u8 |
| test_account | u8 (1 = yes) |
| health_milli / absorption_milli | i32 |
| food | u16 |
| totem_count | u16 |
| hotbar_totem | u8 |
| attrDamage, attrSpeed, attrRange, attrMove, attrJump, attrScale, attrKbRes, attrGravity | i32 × 8 |
| effectsPacked0, effectsPacked1 | i32 |
| clientBrand, serverId, kit, world, mapId, mcVersion, grimVersion, peVersion | u8 length + utf-8 (max 255) |
| **GML3 only:** taxonomy_version i32, taxonomy_hash i64 | |
| compressed_len | i32 |
| compressed | zlib |

`relativePositions` is applied at encode time (subtract first frame xyz). The file does **not** store a “relative” flag; decoded xyz are whatever was written.

## Compressed body

### GML1 body

Stream of records, no count prefix.

**ROTATION (1):** `i32 tick, f32 yaw, f32 pitch, i64 nanos` — yaw/pitch are **degrees**, not centi.

Then events until EOF:

| type | extra after `u8 type, i32 tick, i64 nanos` |
|---|---|
| ATTACK (2) | i32 targetEntityId, i32 hitPacked, u16 ping |
| STATE (3) | u16 flags, u16 ping |
| GRIM_FLAG (4) | i32 checkNameHash, u16 ping |
| anything else | encoder wrote type/tick/nanos only; decoder **fails the file** |

No xyz, no MARKER, no frames.

### GML2 / GML3 body

`u16 frameCount` then that many FRAME records, then `u16 eventCount` then that many events.

**FRAME (5):**

- u8 type (=5), i32 tick, i32 nsOffset, u8 order, u16 tickDur
- frame 0: i32 x,y,z (milliblocks), i32 yaw,pitch (**centidegrees**)
- later frames: 5 zigzag varints of **deltas** vs previous (xyz and yaw/pitch)
- i32 flags, u8 pose, u8 slot, u8 heldCat, i32 heldId, u8 offCat, i32 offId
- u8 packetsThisTick, i16 ping, i16 jitter, i32 ackTx, i32 groundY milli, i32 fall milli, i32 vehType, i32 vehId

Varint: unsigned-shift 7-bit, zigzag `(u>>>1)^-(u&1)` matching `BinaryFormat.writeVar` / `readVar`.

**Events (type 2–4, 6–26):** fixed layout regardless of type

`u8 type, i32 tick, i32 nsOff, u8 order, i64 nanos, i64 a, i32 b, i32 c, i32 d, i32 e, i32 f, u16 ping`

Unknown type **fails the file**. Type 5 in the event stream is illegal (frames already consumed).

## Record types (`RecordTypes.java`)

1 ROTATION, 2 ATTACK, 3 STATE, 4 GRIM_FLAG, 5 FRAME, 6 DAMAGE, 7 SWING, 8 USE_ITEM, 9 HELD_SLOT, 10 CLICK, 11 BLOCK_PLACE, 12 KNOCKBACK, 13 TOTEM, 14 SHIELD, 15 MARKER, 16 CONSUMABLE, 17 TELEPORT, 18 SETBACK, 19 PROJECTILE, 20 WINDOW, 21 RTT, 22 BREAK, 23 BUCKET, 24 ELYTRA, 25 STATUS, 26 MARKER_KV. `TYPE_COUNT = 27`.

## Event field packing (GML2/3 `a..f`)

From capture/listener writers. `a` is i64, rest i32.

| type | a | b | c | d | e | f |
|---|---|---|---|---|---|---|
| ATTACK | target **anon** id | target entityId | hand \| (sneak<<1) \| (heldCat<<8) | hitPacked xyz milli | heldId | lastAckTx |
| DAMAGE | other anon | raw×100 | final×100 | flags (crit/blocked; victim has bit 8) | weapon cat | fall milli **or** hpBefore on victim |
| SWING | hand | 0 | 0 | 0 | 0 | 0 |
| HELD_SLOT | from slot | to \| (scroll<<8) | fromCat \| (toCat<<8) | fromId | toId | 0 |
| MARKER | wall clock **ms** | packed: action u8 \| (source<<8) \| (kvCount<<16) | labelId | clientId | moduleId | settings hash |
| MARKER_KV | FNV-1a key hash | FNV-1a val hash | 0 | 0 | 0 | 0 |
| GRIM_FLAG | check hash | (other fields from hook) | | | | |
| SETBACK | (position-ish; GrimAPI has no prediction offset) | | | | | |

**MARKER action:** 0 end, 1 start, 2 interrupted. Source staff = 1. Label ids: 1 LEGIT_CONFIRMED, 2 CHEAT.

`mark-id=` is an extra MARKER_KV (hashes only, not the plaintext id). Plaintext id is in `marks.csv` and the filename `yyyy-MM-dd_m########_<rand>.gmlml`.

FNV-1a 32-bit: offset `0x811c9dc5`, prime `0x01000193`, xor then multiply per UTF-16 code unit (`String.charAt`).

## Frame flags (`FrameRing`)

`F_ON_GROUND=1`, `HORIZ_COLL=1<<1`, `INPUT_W..D = 1<<2..5`, `JUMP=1<<6`, `SNEAK=1<<7`, `SPRINT=1<<8`, `INPUT_INFERRED=1<<9`, `CLIENT_TICK_END=1<<10`, `JUMP_EDGE=1<<11`.

Header `stateFlags` (`RecordTypes`): SPRINT=1, SNEAK=2, VEHICLE=4, ELYTRA=8, BEDROCK=16, STAFF=32, TEST_ACCOUNT=64, VANISHED=128, CREATIVE_FLIGHT=256, MAIN_HAND_LEFT=512, BLOCKING=1024, USING_MAIN=2048, USING_OFF=4096, IN_WATER=8192, IN_LAVA=16384, IN_WEB=32768, CLIMBING=65536.

Validity bits: PARTIAL=1, LAG=2, DEATH=4, RESPAWN=8, TELEPORT=16, SETBACK=32, WORLD=64, GAMEMODE=128, VANISH=256.

Header `label_source`: 0 unlabeled, 1 staff-marker, 2 ban-backfill, 3 replay-confirmed, 4 grim-weak, 5 grim-player.

## Marker join (training)

Staff `/grimml mark` writes MARKER start/end into the **player ring**, flushed in later `.gmlml` windows. Reconstruct segments by `(anon_player_id, session_id)` ordered by MARKER `a` (wall ms). Windows whose `[epoch_ms, epoch_ms + duration_ms]` overlaps a trimmed segment inherit that label.

GML1 files **cannot** carry staff marks. Join then depends on sidecars (`marks.csv`, `grim-labels.csv`, `grim-review.csv`, `roster.csv`) or `cheat_log.csv`.

Interrupted marks (`action=2` or a start replacing an open segment) are excluded when `exclude_interrupted: true`.

## Sidecars (plugin data folder, not inside `.gmlml`)

| file | who | ids |
|---|---|---|
| `sessions.idx` | writer thread | anon base36, mark id, wall ms, label/client/module, kv |
| `marks.csv` | staff ledger | plaintext mark id, operator **name**, account **name**, anon, settings. **not training input** |
| `grim-labels.csv` / `grim-review.csv` | grim-evidence | anon ids only |
| `grim-checks.json` | FlagEvent names | hashes |
| `roster.csv` | LiteBans/vanilla ban | uuid in roster **only**; sidecar lines use anon |
| `taxonomy.yml` | clients/modules | 1-based yaml order ids on MARKER |

## Filenames

- auto: `yyyy-MM-dd_<anon36>_<session36>_<8hex>.gmlml`
- marked (2026-11-02+): `yyyy-MM-dd_m########_<8hex>.gmlml`

`file_date` is the `yyyy-MM-dd` prefix. `mark_id` is `m` + 8 hex when present.

## Item categories (`ItemCategories`)

0 other, 1 sword, 2 axe, 3 mace, 4 spear, 5 trident, 6 bow, 7 crossbow, 8 shield, 9 totem, 10 gapple, 11 egapple, 12 potion, 13 pearl, 14 wind_charge, 15 cobweb, 16 block, 17 water_bucket, 18 lava_bucket, 19 food, 20 fist.

## Ambiguous / not in Java anymore

- **GML1 ATTACK `eventA`** is entity id (not anon). GML2+ ATTACK `a` is anon, `b` is entity id. Do not mix.
- **GML1 yaw/pitch** are float degrees; GML2+ frames are int centidegrees. Decoder stores GML1 as `round(deg*100)` into `yaw_centi` so features share a scale — that conversion is ours, not on disk.
- **hitPacked** xyz layout: `AttackCapture.packHit` milliblock clamp into one i32. Not decoded into x/y/z yet.
- **DAMAGE packFlags** bits besides crit/blocked/victim: read `DamageListener.packFlags` before using.
- **effectsPacked** bit layout: not documented in RecordTypes; treat as opaque.
- **GML1 default event type** (encoder `default ->` empty extra): if a GML1 file ever contains type >4, fail the file rather than skip.
- No Java decoder exists; training Python is the only reader. Byte-diff against `BinaryFormatSelfCheck` output is still outstanding (AUDIT A2).
