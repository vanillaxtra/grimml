# decisions

## 2026-10-30 - grim-auto labels are weak suspects, never train grim features on them

- FlagEvent is a weak signal. Auto-labels are `GRIM_WEAK` (window) and `GRIM_PLAYER_SUSPECT` (player sidecar), never `CHEAT_CONFIRMED`
- netty only copies primitives into a bounded MPSC `FlagQueue`; the `grimml-evidence` thread (1 Hz) decays scores, applies lag filters, and writes sidecars
- unknown check names get weight 0 in `grim-weights.yml` and stay in `grim-checks.json` for review
- staff-marker and replay-confirmed window labels are never overwritten
- sidecar files use anon ids only
- **Grim-derived features must not be inputs to models trained on grim-auto labels.** those labels exist so combat behavior grim does *not* cover can be pretrained; feeding grim flags back in just copies grim. evaluate only on staff/replay-confirmed labels. weight weak positives by confidence and sample ~100 for staff review before trusting precision

## 2026-10-29 - taxonomy.yml owns clients/modules; grim only supplies check names

- grim detects violations and does not know cheat clients or modules. GrimAPI 1.3.2.1 has `FlagEvent` → `check.getCheckName()` and per-user `GrimUser.getChecks()`, not a global list of every check
- clients, modules, expected grim names, and setting schemas live in `plugins/grimml/taxonomy.yml`. numeric ids are 1-based yaml order; version plus sha-256-64 of the file go on the GML3 header and `sessions.idx`
- `grim-checks.json` is learned at runtime from FlagEvent (and seeded from taxonomy `expected_grim` plus `getChecks()` on join). FlagEvent looks up the ring by uuid and never calls bukkit. file flush is async every 5s
- `/grimml mark` is a time-range segment on one account, not an account-wide label. quit and disable close open segments with `interrupted`. settings validate against the module schema; `expected=` is a free grim-check name for tab complete
- module detection is the training target; client id is supporting evidence only and brand strings are not trusted

## 2026-10-29 - schema v3 markers

- GML3 magic, schema 3; GML1/GML2 stay identifiable by magic and are not rewritten
- header adds taxonomy version and hash after the version strings
- MARKER `eA` is wall-clock ms, `eB` packs action/source/kv-count, `eC` label id, `eD` client id, `eE` module id, `eF` settings hash; following `MARKER_KV` events hold key/value hashes
- test-account flag is already in the GML2 header byte and `FLAG_TEST_ACCOUNT`; uuids persist in `test-accounts.txt` and are applied on join

## 2026-10-29 - litebans combat bans are suspect not cheat_confirmed

- litebans `entryAdded` is async so the hook only appends `roster.csv` and a `sessions.idx` line; no bukkit calls
- label is always `SUSPECT` with `valid_from` = now minus `ban-labels.backfill-days` (14); that is exclusion/validation, not a positive cheat class
- only reasons matching `ban-labels.combat-reason-keywords`; empty list matches nothing
- vanilla `PlayerKickEvent` with a BAN cause is a fallback and is deduped against litebans for 10s
- uuid stays in the roster file only, never in `.gmlml` recordings; sidecar uses the hashed anon id

## 2026-10-29 - schema v2 fight windows

- GML2 magic, schema 2; GML1 files remain identifiable by `GML1` and are not rewritten
- per-tick frames replace rotation-only rings; seqlock per slot so the writer can copy other players without tearing or blocking netty
- fight windows: on flush the writer copies attacker, target (entityId to anonId), and up to 4 bystanders within range using last milliblock positions, no bukkit on that thread
- target anon id is written on every attack; raw entity ids stay as a secondary field
- window validity bits mark death respawn teleport setback world gamemode vanish lag overflow instead of dropping the window
- header carries wall-clock epoch paired with nanoTime plus boot id, server-id, kit, map-id
- session join/quit and grim check-name lines go to `sessions.idx` on the writer thread
- `/grimml mark` writes a MARKER event so cheat sessions can be self-labelled
- main-thread potion/attribute/health reads are staggered (`stagger-players-per-tick`)
- positions default to absolute (ffa); `position-mode: relative` subtracts the first frame of the window at encode time
- player input packet is used when PacketEvents delivers it; otherwise movement/sprint/sneak bits stay inferred from flying and bukkit toggles
- grim transaction ping and last received tx are sampled on the staggered main-thread pass, not on netty

## 2026-10-29 - disk cap walk off the server thread

- `StateSampler.sampleAll` was calling `DiskCapGuard` on the main thread. `Files.walk` plus `File.isFile` plus `Files.size` stat'd every recording on the tick and showed up as ~97% of the server thread
- the walk now lives on a daemon thread named `grimml-disk`, started from `onEnable` and joined from `onDisable`. it sleeps `safety-check-interval-ms` between passes and reads that value each cycle so `/grimml reload` applies
- file size comes from the `BasicFileAttributes` the walk already fetched, so each file is lstat'd once. an interrupt mid-walk stops the walk and does not pause from a partial total
- MSPT sampling stays on the main thread. `Bukkit.getAverageTickTime` is a server-thread call

## 2026-10-28 - Phase 1 recorder only

- no detection, no punishments, no ML inference; only capture combat windows to disk
- keep PacketEvents path allocation-free and lock-free; metadata sampling lives on the main thread
- custom compressed binary `.gmlml` instead of Parquet; smaller footprint, no extra jar dependencies
- combat-only recording: rolling 5s pre-buffer + 2s post-buffer per player, flushed only around attacks
- hit offset not computed in phase 1; requires entity lookup so it is deferred to offline feature extraction
- anonymize player ids with salted SHA-256; session ids are random per login
- Java 21 bytecode targets both 1.21.1 and 26.x servers; PacketEvents covers protocol differences
- GrimAPI `1.3.2.1` is provided and used only as a soft-depend; plugin still loads without Grim
- writer uses a bounded queue and drops newest snapshots if it falls behind; never stalls the packet path
- auto-pause on high MSPT and disk cap are advisory; manual `/grimml pause` also available
- comments are single-line, lowercase, no punctuation, only what the code cannot say
