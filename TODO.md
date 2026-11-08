# grimml: what's done and what's left

**Status basis:** branch `rev` as of 2026-11-08 (GML3 schema 3, record types up to 30, config v5). Code items are done; what is left is process (👤), synth calibration that needs real data, and punishments, which stay off until the FP rate holds on real traffic.

Legend: ✅ done in repo · 🔶 partial / needs verify · ⬜ not done · 👤 ops (you on servers/process, not code)

---

## ✅ Phase 1 baseline (original README scope)

- [x] Combat-only windows (pre/post buffer, configurable)
- [x] Rotation / attack / state capture (superseded by per-tick **FRAME** records in GML3, still conceptually the same data)
- [x] Client brand, protocol, ping, Bedrock (Geyser/Floodgate)
- [x] SHA-256 salted anonymization, random session IDs
- [x] No-allocation packet hot path, seqlock rings
- [x] Async writer, bounded queue, drop-newest under load
- [x] MSPT auto-pause, disk cap, manual pause/resume
- [x] Commands: `status`, `pause`, `resume`, `reload`
- [x] Binary self-check encoder/decoder (GML3 round-trip)
- [x] `docs/` maintained

---

## ✅ Labeling & staff workflow (plugin code)

- [x] `taxonomy.yml` — clients, modules, settings, `expected_grim`, **`client-names`** (tab-complete for free-text cheat names)
- [x] `/grimml mark start|end` — time-range segments, console/RCON, `key=value` settings + **`client-name=`**
- [x] Mark session ids (`m########`) in chat, filenames, `marks.csv`, `/grimml mark list|get`
- [x] Tab complete: accounts (test-first), labels, clients/modules, settings, `expected=`, `client-name=`
- [x] MARKER + MARKER_KV records; taxonomy version + hash in GML3 header
- [x] Session sidecar lines (join/quit, markers, taxonomy, grim checks, test-account, ban suspect, grim-weak)
- [x] `/grimml testaccount` + header test-account flag
- [x] Auto-close open marks on quit/disable with **interrupted**
- [x] Learn Grim check names from `FlagEvent` → `grim-checks.json`
- [x] `label_source` on windows (staff / ban-backfill / grim-weak / replay-confirmed; protected labels not overwritten)
- [x] **`recording-mode: mark-only`** — `.gmlml` only for the marked player while a mark is open (events still buffer for everyone)
- [x] **Mark action bar** — live quality hint (ping, jitter, mspt, frame count, label/module/client-name) while mark is open

---

## ✅ Grim evidence auto-labeling (weak labels, plugin code)

- [x] Flag hook → bounded MPSC `FlagQueue` (primitives only on netty thread)
- [x] `grim-weights.yml` — per-check weight, category, trust, lag/promote params
- [x] 1 Hz tracker: decay, lag filter, promotion rules
- [x] Window `GRIM_WEAK` stamp + sidecars `grim-labels.csv` / `grim-review.csv` (anon ids only)
- [x] `/grimml suspects`, `/grimml review next|mark ...`
- [x] Documented: never train grim-derived features on grim-auto labels
- [x] Ban hook: LiteBans via **`LiteBansBridge`** (loads only if API present) + vanilla kick fallback → SUSPECT backfill

---

## ✅ Synthetic data (`grimml-synth` sibling repo)

Repo: `C:\Users\Admin\Documents\grimml-synth\` (Python 3.11, pytest passing).

- [x] Human baseline + mouse quantization
- [x] Module transforms (aim assist, killaura, triggerbot, autoclicker, reach, anti-KB, scaffold, macros, etc.)
- [x] Styles / holdout tiers
- [x] CLI → parquet (`python -m grimml_synth.build ...`)
- [x] Eval guard: synthetic never in test split (`assert_not_synthetic`)
- [x] `calibrate.py` + `from_real.py` stubs for fitting from real sessions
- [ ] 🔶 Calibrate parameter ranges from **your** real test-account `.gmlml` sessions (tooling exists, needs data)

---

## ✅ Recorder GML3 (was "v2 P0/P1/P2" in old list)

All capture items are implemented. What's left is checking the new records (retotem, jump reset, STAB, map snapshots) on a live server.

### P0 — aim/reach/killaura training blockers

- [x] Schema **GML3** (GML1/GML2 identifiable by magic; not rewritten on disk)
- [x] Old **GML1 files fully decode** in `training/grimml_train/decode/` (pytest fixtures for GML1/2/3)
- [x] Per-tick x/y/z in FRAME records (+ optional relative mode)
- [x] Fight windows: fight id, attacker + target + nearby bystanders, roles
- [x] Target **anon id** on attack/damage (entity id also stored where needed)
- [x] Session-scoped entityId → anonId (`EntityIndex`)
- [x] Writer-thread seqlock copy (`FightAssembler`; packet path never blocks on other players)
- [x] Ack ticks on frames / RTT events
- [x] Sub-tick: ns offset + per-tick order index
- [x] Held/offhand category + id (`InventoryMirror`)
- [x] DAMAGE events (attacker/victim, raw/final, crit, blocked, shield disable hints, health)
- [x] MSPT/tick duration in window header
- [x] Validity flags: partial, lag, death, respawn, teleport, setback, world, gamemode, vanish
- [x] Header: server id, kit, map id, world, boot id, epoch ms, MC/Grim/PE versions, handedness, staff/test/vanish flags, taxonomy hash
- [x] **Map files** per map id + world: `maps/<map-id>__<world>.gmlmap` (GMM1) at first combat or `/grimml mapsave`; `training/grimml_train/maps` reads it and does LOS
- [x] Session index sidecar + grim check name table via `grim-checks.json` + sidecar lines

### P1 — macros and timing

- [x] Input bits (W/A/S/D, jump, sneak, sprint) when protocol sends `PlayerInput`
- [x] Pose / using / blocking / water-lava-web-climbing flags (sampler + packets)
- [x] Swing, use-item, held-slot swap events
- [x] Shield / totem / knockback records (combat triggers for mace/knockback jump)
- [x] Potion + attributes on staggered main-thread sampler (scale, range, etc.)
- [x] Armor/held/offhand **enchants in binary** — ENCHANT record (27) per window, packed levels from set-slot / window-items
- [x] Block place (+ bucket context where implemented)
- [x] Staggered main-thread reads (`StateSampler`)

### P2 — advanced

- [x] Inventory click + window open/close events (basic)
- [x] Mace/spear **rich** records — COMBAT_DETAIL (28): mace smash (fall, damage, enchants, ms since wind charge), spear charge hold / STAB / hit, wind charge timing
- [x] Retotem latency / click-failure detail — RETOTEM (29): pop to offhand totem ns, method (click / swap key / failed after 5 s), clicks and extra clicks, inventory opened
- [x] Jump-reset **window** — JUMP_RESET (30): first jump offset and jump/ground/sprint/W bits for −5..+10 ticks around each knockback
- [x] PROJECTILE (19) emitted on launch (kind, velocity) and on player hit (target anon, hit offset, distance)
- [x] Grim setback events; prediction offset 🔎 Grim API
- [x] Bystander snapshots in fight assembler
- [x] Elytra/glide events (partial)

---

## ✅ Storage, format, performance

- [x] Delta positions/rotations + **Deflate** on record blob (not zstd)
- [x] Per-record-type drop counters — records inside windows dropped on a full queue, by type, in `/grimml status` (ring overwrites are normal and no longer mark windows partial)
- [x] Hourly rotation (`rotation: hourly` → `<day>/<hour>/`) + daily offload (`offload.*`, moves finished days on the disk thread; disk cap auto-resumes)
- [x] 👤 Perf check on live servers — **you reported drops ~0 and MSPT fine** (formal 80-bot script not recorded in repo)
- [x] Round-trip tests for every record type — `BinaryFormatSelfCheck` checks every header/frame/event field; pytest decodes the same Java file
- [x] Folia — Paper region/global/async/entity schedulers, `folia-supported: true` (not yet run on a live Folia server)
- [x] Published **release builds** — push a `v*` tag, `.github/workflows/release.yml` attaches the jar; `ci.yml` runs the self-checks + pytest on every push

---

## 👤 Legit data labeling (section 7 — process)

- [ ] Fill roster: staff, opted-in, trusted players
- [ ] `LEGIT_CONFIRMED` only for staff / alts / opted-in during legit marks
- [ ] 2–3 week rule before `LEGIT_PROBABLE`
- [ ] Backfill banned/reported as SUSPECT (plugin helps via bans; roster is manual)
- [ ] Recruit top PvPers as confirmed-legit hard positives
- [ ] Hard negatives: high-DPI, controller, Bedrock, high-ping
- [ ] Privacy policy update for behavior recording

---

## 👤 Cheat data collection (section 8 — process)

- [ ] Test arena / mirror server (same Grim, kit, map)
- [ ] Isolated VM for clients from official sources only
- [ ] Session sheet discipline (operator, client, module, settings, ping, notes)
- [x] 👤 **Pilot started** — cheat sessions with `/grimml mark` on test accounts (expand operators/clients/modules per schedule below)
- [ ] Full collection schedule (blatant/moderate/subtle, interleaved legit/cheat)
- [ ] 4–6 operators, 3–5 clients, network limiter, real opponents
- [ ] Hold one client out for eval; re-collect after client updates
- [ ] Optional overnight bot scheduler

---

## ✅ Training pipeline (`training/`)

See **`training/TODOtraining.md`** for detail.

- [x] `.gmlml` decoder → parquet (GML1/GML2/GML3) in `training/grimml_train/decode/`
- [x] Label joiner (sidecars + priority rules) — marker time-range trim + `marker_min_overlap` share inside the segment
- [x] Inventory report (basic)
- [x] Feature extraction + grouped splits + LightGBM baseline (minimal)
- [x] Eval breakdowns (bedrock, ping bucket, client, module), multi-task (legit/aim/macro/other), grouped CV, dual aim vs macro models, ONNX parity on every row

---

## 🔶 Alert-only inference (Phase 4)

- [x] Async scoring on `grimml-inference` (LightGBM text model walked in Java, exact parity with Python; ONNX Runtime skipped on purpose, see decisions 2026-11-08)
- [x] Staff alerts (`grimml.alerts`) + decayed violation buffer + `MODEL_ALERT` review rows that `/grimml review mark` turns into labels
- [ ] 👤 3–4 weeks alert-only tuning (set `inference.enabled: true` with an exported model)
- [x] Latency / MSPT monitoring — scoring latency last/avg/max, queue, drops in `/grimml status`; recorder MSPT pause also stops scoring

---

## ⬜ Punishments & ongoing

- [ ] 👤 Punishments only after FP rate acceptable — deliberately not built (multi-detection, Grim combo, kick/setback before ban)
- [ ] 👤 Monthly retrain (schedule) · [x] drift monitoring: `grimml-train drift` (PSI per feature vs `drift_reference`)
- [x] Docs + privacy re-audit — `docs/privacy-audit.md` (2026-11-08); map snapshots off in relative mode

---

## Milestones

| Milestone | Repo status | Done when (your bar) |
|---|---|---|
| **M1: Recorder live** | ✅ **you:** deployed all servers, perf OK | Formal bot benchmark + map files still optional |
| **M2: Labels working** | 🔶 pilot cheat marks in progress | Roster + legit marks + full operator schedule still open |
| **M3: First model** | 🔶 pipeline complete, needs labeled data | Beats baseline on held-out operator **and** client |
| **M4: Alert-only** | 🔶 code done (`inference.enabled`) | Staff reviewing live alerts |
| **M5: Punishments** | ⬜ | FP rate holds on real traffic |

---

## Nice-to-haves (from sessions, not on old list)

- [x] `/grimml mode markonly on|off` at runtime (until reload/restart)
- [x] Action bar also shown to the **staff operator** when mark target ≠ operator
- [x] README badge/phase text updated
