# grimml

![license](https://img.shields.io/badge/license-MIT-blue) ![java](https://img.shields.io/badge/java-21%2B-orange) ![paper](https://img.shields.io/badge/paper-1.21.1%20--%2026.x-lightgrey) ![status](https://img.shields.io/badge/phase-1%20recorder-yellow)

A Paper plugin that records anonymized PvP combat data next to [Grim Anticheat](https://github.com/GrimAnticheat/Grim), to build a dataset for machine-learning cheat detection.

## Why

Rule-based anticheats like Grim are good at catching movement and protocol cheats. Combat cheats such as aim assist and subtle killaura are harder to pin down with fixed rules. A model trained on real fights could catch them, but that first needs clean, labelled combat data. grimml collects that data on a live server without hurting performance.

**This is Phase 1: recorder only.** It does no detection, no ML inference and no punishments. It writes combat windows to disk, and that's all.

## Features

- **Combat-only recording.** A rolling pre-buffer (5s by default) and a post-buffer (2s) are kept per player, and a window is only saved around attacks.
- **Captures:** per-tick position and rotation, input bits when the protocol sends them, attacks with target anon ids, damage, held item, inventory clicks, shield/totem/knockback, Grim flags, ping, client brand/protocol, and Bedrock via Geyser/Floodgate.
- **Weak labels from Grim:** when GrimAC is installed, its flags are stored in the same window.
- **Anonymized:** player UUIDs are hashed with SHA-256 and a salt, and session ids are random per login. Raw UUIDs never reach disk.
- **Built not to lag the server:** the packet path neither allocates nor locks. A single async writer uses a bounded queue and drops the newest windows instead of blocking. The disk-cap walk runs on its own thread, not on the server tick.
- **Safety valves:** recording auto-pauses on high MSPT, auto-pauses over a disk cap, and can be paused by hand.

## Requirements

| | |
|---|---|
| Server | Paper (or a fork such as Leaf), 1.21.1 through 26.x |
| Java | 21+ |
| Required plugin | [PacketEvents](https://github.com/retrooper/packetevents) 2.14.0+ |
| Optional plugins | GrimAC (flag labels), Geyser-Spigot / floodgate (Bedrock flag) |

## Installation

There are no release builds yet, so build it yourself (needs JDK 21+ and Maven):

```bash
git clone https://github.com/vanillaxtra/grimml
cd grimml
mvn clean package
```

1. Copy `target/grimml-0.1.0-SNAPSHOT.jar` and PacketEvents into `plugins/`.
2. Start the server once to generate `plugins/grimml/config.yml`.
3. **Change `anonymize-salt`** to a random secret value, then run `/grimml reload` or restart.

Recordings are written to `plugins/grimml/recordings/`.

## Usage

All commands need `grimml.admin` (op by default).

| Command | What it does |
|---|---|
| `/grimml status` | enabled/paused state, windows written and dropped, bytes written |
| `/grimml pause` | stop saving new windows |
| `/grimml resume` | resume saving |
| `/grimml reload` | reload `config.yml` |
| `/grimml mark <start\|end> <label> [client] [module] [settings]` | write a MARKER into this session |
| `/grimml testaccount [on\|off]` | flag this session as test traffic |

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | master switch |
| `anonymize-salt` | `change-me` | salt for player id hashing; set your own and don't reuse it across public datasets |
| `pre-combat-seconds` | `5` | history kept before an attack |
| `post-combat-seconds` | `2` | time recorded after the last attack |
| `writer-interval-ms` | `2000` | how often the writer flushes to disk |
| `writer-queue-capacity` | `256` | queued windows before new ones are dropped |
| `ring-capacity` | `128` | rotation samples kept per player |
| `event-ring-capacity` | `64` | attack/state/flag events kept per player |
| `mspt-pause-threshold` | `50.0` | auto-pause above this MSPT (`0` disables); resumes below 80% of it |
| `safety-check-interval-ms` | `5000` | how often the disk-cap walk runs on its own thread |
| `max-disk-mb` | `10240` | auto-pause once recordings exceed this size (`0` = unlimited) |
| `recordings-dir` | `recordings` | output folder, relative to the plugin folder |

## Output format

Each combat window becomes one file: `<date>_<anonId>_<session>_<rand>.gmlml`. New files use magic `GML2` (schema 2). Existing `GML1` files stay on disk unchanged.

- **Header:** fight/window ids, role, trigger, validity, session/anon ids, wall-clock epoch + nanos, boot id, ping/protocol, kit/server/map, versions.
- **Body:** Deflate-compressed per-tick frames (delta-encoded positions) plus events (attack, damage, swing, use-item, held-slot, click, place, knockback, totem, shield, marker, grim, …).
- **Sidecar:** `recordings/sessions.idx` join/quit/mark/grim-hash lines.

Attack records store the target **anon id**, not only a raw entity id. A fight flush snapshots the attacker, the defender, and nearby bystanders.

The encoder lives in `format/BinaryFormat.java`, and `format/BinaryFormatSelfCheck.java` is a working decoder example.

## How it works

```mermaid
flowchart LR
    A[PacketEvents netty thread] -->|rotations, attacks| B[PlayerRingBuffer]
    C[Main thread sampler] -->|state, ping| B
    D[Grim flag hook] -->|weak labels| B
    B -->|snapshot if in combat| E[Async writer]
    E -->|compress| F[.gmlml file]
```

More detail is in [docs/architecture.md](docs/architecture.md) and [docs/decisions.md](docs/decisions.md).

## Non-goals (Phase 1)

- No detection, alerts or punishments.
- Hit-offset is stored when the attack packet carries a hit vector; full aim error is computed offline from attacker and target frames.
- No Folia support yet.

Open items are tracked in [docs/known-issues.md](docs/known-issues.md).

## Contributing

```bash
mvn clean package               # build + binary format self-check
mvn clean package -DskipTests   # build only
mvn exec:java                   # self-check on its own
```

- One folder per function and one file per feature (for example `commands/StatusCommand.java`).
- Keep the packet path free of allocations and locks.
- Comments are single-line and lowercase, and only explain what the code can't.
- Update `docs/` when behaviour changes.

## License

[MIT](LICENSE)
