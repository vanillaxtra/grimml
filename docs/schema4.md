# GML schema 4

Schema 4 is schema 3 plus **extension blocks**: the header, every frame and every event end with

```
ext := varint byteLength, then entries until byteLength is used up
entry := u8 tag, varint len, len bytes of payload
```

A decoder reads the tags it knows and skips any other tag by its length, so new data never needs a schema 5:
add a tag here, write it in the recorder, read it in the decoder. Old decoders cannot read schema 4 (they stop at the
new magic `GML4`), and schema 4 decoders still read schema 1-3 unchanged.

All fixed schema 3 fields keep their meaning, so features computed from them (the 13 model features) are identical
on schema 3 and schema 4 windows. Integers in payloads are zigzag varints (`zz`) unless noted; `f32` is big-endian.

## Header extension

No tags yet (empty block, one byte).

## Frame extension tags

| tag | name | payload | meaning |
|---|---|---|---|
| 1 | ROT_EXACT | f32 yaw, f32 pitch | rotation exactly as the client sent it (the fixed fields keep the 0.01° rounding) |
| 2 | CLIENT_TICK | zz | client tick number: +1 per CLIENT_TICK_END packet this session (1.21.2+ clients; 0 for older ones). A frame closed by a tick-end carries that tick's number |
| 3 | OPPONENT | i64 anon, zz dx, zz dy, zz dz, zz yaw, u8 pose, u8 state, zz blockAgoMs, zz hurtAgoMs, u8 nearby | present when the player has an opponent (below). dx/dy/dz = opponent minus player position in mm; yaw in centidegrees; state bits: 1 on ground, 2 sprinting, 4 sneaking, 8 blocking with a shield, 16 gliding, 32 using an item; blockAgoMs = ms since the opponent last raised or lowered a shield, hurtAgoMs = ms since the opponent last took damage (-1 = never / unknown, capped at 65535); nearby = players within 6 blocks |
| 4 | PACKET_TIMING | zz maxGapMs, u8 burst | longest silence between this player's inbound packets during the frame, and how many inbound packets came less than 2 ms after the previous one |

**Opponent**: the player you hit in the last 5 s, else the player who hit you in the last 5 s, else the nearest player
within 6 blocks. Picked on the main thread by the state sampler; positions are each player's latest movement packet.

## Event extension tags

| tag | name | payload | meaning |
|---|---|---|---|
| 1 | CLIENT_TICK | zz | client tick number when the event happened (see frame tag 2) |
| 2 | EXTRA | zz g, zz h, zz i, zz j | four more values; meaning depends on the event type (below). Written only when one of them is non-zero |

## New event types

| type | name | a | b | c, d, e | f | extra |
|---|---|---|---|---|---|---|
| 27 | input | key bits: 1 W, 2 A, 4 S, 8 D, 16 jump, 32 sneak, 64 sprint | | | | |
| 28 | move_state | 1 sprint start, 2 sprint stop, 3 sneak start, 4 sneak stop | | | | |
| 29 | entity_sent | entity id | kind: 1 relative move, 2 relative move + rotation, 3 teleport, 4 position sync | kinds 1-2: movement delta in mm; kinds 3-4: entity position minus the receiving player's position in mm | 1 if the entity is the current opponent | g = yaw centidegrees (kinds 2-4) |
| 30 | rtt_sent | transaction / keep-alive id | 1 transaction (ping), 2 keep-alive | | | |
| 31 | entity_spawn | entity id | 1 end crystal, 2 wind charge, 3 TNT, 4 TNT minecart | position minus the player's in mm | | |

`entity_sent` is written only for the current opponent (that is what backtrack features need); `entity_spawn` only
within 16 blocks. Transaction replies are `rtt` events with c = 1 (a = id). Grim consumes its own transaction pongs before other
PacketEvents listeners see them, so the recorder takes them from Grim's `GrimTransactionReceivedEvent` (needs Grim);
keep-alive replies are `rtt` events with c = 2. A transaction's exact round trip is `rtt.nanos - rtt_sent.nanos` for the
same id.

## Extra values on existing events

| event | g | h, i, j |
|---|---|---|
| attack | target kind (0 unknown, 1 player, 2 end crystal) + 256 × attack-cooldown ticks (server ticks since the last swing or hotbar change, capped at 255) | hit point on the target's hitbox × 1,000,000 (only when the client sent one) |
| block_place | clicked block: 0 unknown, 1 respawn anchor, 2 obsidian, 3 bedrock, 4 glowstone, 5 other; + 256 × anchor charges | |

## What it enables

- **Aim assist / triggerbot**: exact rotation, opponent position and pose every tick (aim error, pull toward target, crosshair-on-target timing), attack cooldown at each hit.
- **Backtrack**: opponent positions the client was sent (`entity_sent`) vs its true positions (OPPONENT), exact transaction round trips (`rtt_sent` + `rtt`), inbound packet gaps and bursts.
- **Auto jump reset / auto W-tap**: every input change and sprint toggle with its client tick, knockback arrival = first transaction sent after the `knockback` event, answered on a known client tick.
- **Crystal / anchor macros**: crystal spawns, attack target kind, clicked block and anchor charges.
- **Shield breaker / stun slam**: opponent shield state and how long ago it changed.
