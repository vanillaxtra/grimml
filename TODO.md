# grimml: what's done and what's left

**Status basis:** repo on `main` as of 2026-11-02 (GML3 schema 3, config v4). Older README still says "Phase 1 only" — the plugin has moved well past that in code; training/inference repos are separate.

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

## 🔶 Recorder GML3 (was "v2 P0/P1/P2" in old list)

Most capture work is **implemented in code**; what’s left is verification, map assets, perf on live servers, and a full **GML1/GML2 decode path** for training (magic detection exists; full multi-version decoder belongs in `grimml-train`).

### P0 — aim/reach/killaura training blockers

- [x] Schema **GML3** (GML1/GML2 identifiable by magic; not rewritten on disk)
- [ ] 🔶 Old **GML1 files fully decode** in a supported decoder (plugin self-check is GML3-only; train pipeline not started)
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
- [ ] ⬜ **Map files** saved per map id (for offline LOS) — only `map-id` string in header today
- [x] Session index sidecar + grim check name table via `grim-checks.json` + sidecar lines

### P1 — macros and timing

- [x] Input bits (W/A/S/D, jump, sneak, sprint) when protocol sends `PlayerInput`
- [x] Pose / using / blocking / water-lava-web-climbing flags (sampler + packets)
- [x] Swing, use-item, held-slot swap events
- [x] Shield / totem / knockback records (combat triggers for mace/knockback jump)
- [x] Potion + attributes on staggered main-thread sampler (scale, range, etc.)
- [ ] 🔶 Armor/hotbar **enchants in binary** — tracked in `InventoryMirror`, not clearly exported per window
- [x] Block place (+ bucket context where implemented)
- [x] Staggered main-thread reads (`StateSampler`)

### P2 — advanced

- [x] Inventory click + window open/close events (basic)
- [ ] 🔶 Mace/spear **rich** records (smash, charge/lunge detail, wind-charge timing) — triggers exist, not full P2 spec
- [ ] 🔶 Retotem latency / click-failure detail
- [ ] 🔶 Jump-reset **window** (−5..+10 ticks around KB) — knockback trigger only
- [ ] ⬜ PROJECTILE record type defined but **not emitted** yet
- [x] Grim setback events; prediction offset 🔎 Grim API
- [x] Bystander snapshots in fight assembler
- [x] Elytra/glide events (partial)

---

## 🔶 Storage, format, performance

- [x] Delta positions/rotations + **Deflate** on record blob (not zstd)
- [ ] 🔶 Per-record-type drop counters — internal `DropCounters.byType`, **`/grimml status` only summarizes windows + torn frames**
- [ ] 🔶 Hourly rotation — **date prefix** on filenames only; no hourly split / daily offload automation
- [x] 👤 Perf check on live servers — **you reported drops ~0 and MSPT fine** (formal 80-bot script not recorded in repo)
- [ ] 🔶 Round-trip tests for every record type (GML3 self-check covers one path, not exhaustive)
- [ ] ⬜ Folia (still non-goal; Bukkit scheduler)
- [ ] ⬜ Published **release builds** (Maven package only; README says build yourself)

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

## 🔶 Training pipeline (`training/`)

See **`training/TODOtraining.md`** for detail.

- [x] `.gmlml` decoder → parquet (GML1/GML2/GML3) in `training/grimml_train/decode/`
- [x] Label joiner (sidecars + priority rules) — marker time-range trim 🔶
- [x] Inventory report (basic)
- [x] Feature extraction + grouped splits + LightGBM baseline (minimal)
- [ ] Eval breakdowns, multi-task, grouped CV, full ONNX parity

---

## ⬜ Alert-only inference (Phase 4)

- [ ] ONNX Runtime async scoring
- [ ] Staff alerts + violation buffer + review loop feeding labels
- [ ] 3–4 weeks alert-only tuning
- [ ] Latency / MSPT monitoring

---

## ⬜ Punishments & ongoing

- [ ] Punishments only after FP rate acceptable (multi-detection, Grim combo, kick/setback before ban)
- [ ] Monthly retrain, drift monitoring
- [ ] Keep docs + privacy re-audit (no chat, IPs, raw UUIDs, survival coords if relative mode off)

---

## Milestones

| Milestone | Repo status | Done when (your bar) |
|---|---|---|
| **M1: Recorder live** | ✅ **you:** deployed all servers, perf OK | Formal bot benchmark + map files still optional |
| **M2: Labels working** | 🔶 pilot cheat marks in progress | Roster + legit marks + full operator schedule still open |
| **M3: First model** | 🔶 `training/` scaffold | Beats baseline on held-out operator **and** client |
| **M4: Alert-only** | ⬜ | Staff reviewing live alerts |
| **M5: Punishments** | ⬜ | FP rate holds on real traffic |

---

## Nice-to-haves (from sessions, not on old list)

- [ ] `/grimml mode markonly on|off` at runtime (today: config + `/grimml reload`)
- [ ] Action bar visible to **staff operator** when mark target ≠ operator
- [ ] README badge/phase text updated to match GML3 + labeling features
