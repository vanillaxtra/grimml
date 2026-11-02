# grimml: what's done and what's left

Status basis: the public repo README (Phase 1, `GML1` format, 8 commits). If you've built v2 pieces locally or on another branch, tick them off yourself.

Legend: ✅ done · ⬜ to do · 🔎 verify against your version (Paper / PacketEvents / Grim)

---

## ✅ Phase 1: recorder (done, per README)

- [x] Combat-only windows (5s pre-buffer, 2s post-buffer)
- [x] ROTATION records (tick, yaw, pitch, nanos)
- [x] ATTACK records (tick, nanos, target entity id, ping)
- [x] STATE records (sprint, sneak, vehicle, elytra, bedrock flags)
- [x] Client brand, protocol, ping, Bedrock detection (Geyser/Floodgate)
- [x] Grim flag hashes stored as weak labels
- [x] SHA-256 salted anonymization, random session IDs
- [x] No-allocation, no-lock packet path
- [x] Async writer, bounded queue, drops instead of blocking
- [x] Safety valves: MSPT auto-pause, disk cap, manual pause
- [x] Commands: `status`, `pause`, `resume`, `reload`
- [x] Binary format + self-check decoder, docs folder
- [x] Legit data collected (needs labeling, see section 7)

---

## ⬜ 1. Recorder v2, P0 (blocks aim/reach/killaura training)

- [ ] Schema version bump to v2 (old `GML1` files must still decode)
- [ ] Per-tick **x/y/z** for attacker and nearby players
- [ ] **Fight windows**: attacker + target (+ nearby bystanders), linked by a fight ID, with roles
- [ ] **Target anon ID** written into attack/damage records (not the raw entity ID)
- [ ] Session-scoped entity ID → anon ID map
- [ ] Safe cross-thread reads of other players' ring buffers (seqlock/version stamp)
- [ ] **Ack ticks** (transaction/ping-pong) so timing can be ping-compensated 🔎
- [ ] Sub-tick timing: nanos offset + arrival order index per relevant packet
- [ ] Held item category + raw ID, offhand category (inventory mirror from set-slot / window-items packets)
- [ ] **Damage events**: attacker, victim, cause, raw/final damage, crit, blocked-by-shield, shield-disabled, totem popped, health before/after
- [ ] Server tick duration and MSPT per window
- [ ] Window validity flags: `partial`, `lag_affected`, `invalidated_by` (death, respawn, teleport, Grim setback, world change, gamemode change, vanish)
- [ ] Header additions: server ID, mode/kit, world/arena/map ID, boot ID, wall-clock epoch ms, MC/plugin/Grim/PacketEvents versions, handedness, staff/bypass/vanish flags
- [ ] Map files saved per map ID (for offline line-of-sight)
- [ ] Session index sidecar (join/quit reasons, check-name hash → name table)

## ⬜ 2. Recorder v2, P1 (macros and timing)

- [ ] Player input bits (W/A/S/D, jump, sneak, sprint) 🔎 packet availability
- [ ] Pose, using-item, blocking, in water/web/climbing state bits
- [ ] Swing events (including misses) with hand
- [ ] Use-item start/stop (spear charge, bow, shield, eating, pearl, wind charge)
- [ ] Hotbar swap events (from/to slot, categories, scroll vs number key)
- [ ] Shield raise/lower/disable, blocked-hit angle, flicker counts
- [ ] Totem pop records (ack tick, time since damage, offhand vs swap)
- [ ] Knockback records (velocity packet, ack tick, victim state)
- [ ] Potion effects, relevant attributes (incl. scale, interaction range) 🔎 current names
- [ ] Armor + hotbar enchants (mace: density/breach/wind burst; spear: lunge)
- [ ] Block placement records (relative position, face, cursor position, yaw/pitch, sneak, context flags)
- [ ] Staggered main-thread reads (state snapshots spread over ticks)

## ⬜ 3. Recorder v2, P2 (advanced)

- [ ] Inventory click detail, window open/close with ack ticks, pre-opened-inventory flag
- [ ] Mace: fall distance per attack, smash records, wind-charge timing, fall-cancel items
- [ ] Spear: jab vs charge/lunge hits, velocities at hit, mounted flag 🔎
- [ ] Retotem: pop → restore latency, method, click count, failed clicks
- [ ] Jump-reset window (−5 to +10 ticks around knockback, press edges vs held)
- [ ] Bucket/pearl/wind-charge/projectile tracking
- [ ] Grim prediction offset and setbacks 🔎 API
- [ ] Nearby-bystander snapshots
- [ ] Elytra glide and firework events

## ⬜ 4. Storage, format and performance

- [ ] Delta-encode positions/rotations, then Deflate or zstd
- [ ] Per-record-type drop counters in `/grimml status`
- [ ] Hourly file rotation and daily offload to the training machine
- [ ] Perf test with 80 bots/peak players on **each** server (survival last, most entities)
- [ ] Verify drop counters stay ~0 and MSPT is unchanged with recorder on vs off
- [ ] Round-trip tests for every new record type
- [ ] Folia support (known non-goal in Phase 1)
- [ ] Build releases (no release builds yet)

---

## ⬜ 5. Labeling system

- [ ] `taxonomy.yml` (clients, modules, per-module settings schema, expected Grim checks)
- [ ] `/grimml mark start|end <account> <label> [client] [module] [key=value...]`
- [ ] Tab completion: accounts, labels, clients, modules, settings keys, value hints
- [ ] MARKER record type (tick, nanos, wall-clock, anon ID, action, label/client/module IDs, settings)
- [ ] Taxonomy version + hash written to the session index
- [ ] Test-account flag (never mixed with normal player data)
- [ ] Auto-close open segments on quit/restart with an `interrupted` flag
- [ ] Learn Grim check names at runtime from `FlagEvent` → `grim-checks.json` 🔎
- [ ] `label_source` field reserved (unlabeled / staff-marker / grim-auto / ban-backfill / replay-confirmed)

## ⬜ 6. Grim evidence auto-labeling (weak labels)

- [ ] Flag hook copies primitives into a bounded queue (no Bukkit calls on netty threads)
- [ ] `grim-weights.yml`: per-check weight, category, trust
- [ ] Decaying per-player score, promotion rules (≥2 distinct checks or repeats across sessions)
- [ ] Lag filters (ping/jitter, MSPT dip, join grace, teleport, setback)
- [ ] Window-level `GRIM_WEAK` labels and player-level `PLAYER_SUSPECT` sidecar records
- [ ] `/grimml suspects`, `/grimml review next`, `/grimml review mark ...`
- [ ] Rule: Grim-derived features never feed models trained on grim-auto labels
- [ ] Ban-system hook (LiteBans or similar) → auto-backfill `SUSPECT` for the last ~2 weeks

---

## ⬜ 7. Legit data labeling

- [ ] Fill the roster: staff, opted-in players, trusted long-time players
- [ ] `LEGIT_CONFIRMED` only for staff, own alts during legit segments, opted-in players
- [ ] Wait 2-3 weeks before promoting anyone to `LEGIT_PROBABLE`
- [ ] Backfill banned/reported players as `SUSPECT`
- [ ] Recruit 10-20 top PvPers (stun slams, retotems, jump resets) as confirmed-legit
- [ ] Hard negatives: high-DPI, controller, Bedrock, high-ping players
- [ ] Update rules/privacy policy to mention gameplay behavior recording

## ⬜ 8. Cheat data collection

- [ ] Test arena or mirror server (same plugins, Grim config, kit, map)
- [ ] Isolated machine/VM for cheat clients, downloaded from developers' official sites only
- [ ] Only run them on servers you own
- [ ] Session sheet (operator, client + version, module, settings, ping, notes)
- [ ] **Pilot:** 2 operators, 2 clients, 2-3 modules (~2 hours), then verify marker alignment and a quick within-operator model
- [ ] Full schedule: one module at a time, legit/cheat interleaved, blatant / moderate / subtle settings
- [ ] 4-6 operators (include a strong PvPer), 3-5 clients to start
- [ ] Vary ping with a network limiter; fight real opponents (1v1 and crowds)
- [ ] Hold one whole client out for evaluation
- [ ] Re-collect after client updates (every 1-2 months)
- [ ] Optional: bot scheduler for overnight scripted sessions

## ⬜ 9. Synthetic data generator (`grimml-synth`)

- [ ] Human baseline simulator + sensitivity quantization
- [ ] Module transforms: aim assist, snap, killaura, triggerbot, autoclicker, reach, anti-KB, macros, scaffold
- [ ] Generator "styles" with some held out
- [ ] CLI to build labeled parquet datasets
- [ ] Calibrate parameter ranges from real test-account sessions
- [ ] Guard: synthetic data can never appear in a test split

---

## ⬜ 10. Training pipeline (`grimml-train`)

- [ ] `.gmlml` decoder → parquet (all versions)
- [ ] Label joiner (roster + markers + sidecars; `UNLABELED` never becomes legit)
- [ ] Data inventory report (windows per player/session/server, class balance)
- [ ] Feature extraction: rotation derivatives, GCD, click stats, aim error, reach, macro timing deltas, ping context
- [ ] Group splits by player/operator; leave-one-client-out
- [ ] LightGBM baseline with class weights, grouped CV
- [ ] Evaluation, false-positive rate first (per player-hour), breakdowns by Bedrock, ping, skill, client
- [ ] Per-player aggregation (alert after N positives, with decay)
- [ ] Multi-task: cheat vs legit, then module, then optional client (open-set)
- [ ] Separate aim/combat model and timing/macro model
- [ ] ONNX export with feature order, scaler, threshold, and a parity test

---

## ⬜ 11. Alert-only inference (Phase 4)

- [ ] ONNX Runtime inference on an async thread, windows scored when full
- [ ] Staff alerts with score and window link; no punishments
- [ ] Violation buffer with decay
- [ ] Staff review flow (confirmed cheat / confirmed legit / unsure) feeding labels back in
- [ ] 3-4 weeks of alert-only testing; tune thresholds against reviewed alerts
- [ ] Monitor model latency and MSPT impact

## ⬜ 12. Punishments and Grim handoff

- [ ] Only after the false-positive rate is acceptable on real traffic
- [ ] Multi-detection requirement before any action
- [ ] Combine with Grim flags for confidence
- [ ] Start with kick/setback; ban only after the rate holds
- [ ] Kill switch and per-server thresholds

## ⬜ 13. Ongoing

- [ ] Monthly retrain, and after client updates
- [ ] Drift monitoring (alert rate, score distributions)
- [ ] Keep `docs/` and `known-issues.md` updated
- [ ] Re-audit privacy: no chat, IPs, raw UUIDs, or absolute survival coordinates

---

## Milestones

| Milestone | Done when |
|---|---|
| **M1: Recorder v2 live** | P0 deployed on all servers, drops ~0, MSPT unchanged |
| **M2: Labels working** | mark command + roster in use, pilot cheat sessions recorded |
| **M3: First model** | beats baseline on held-out operator **and** client, low false-positive rate |
| **M4: Alert-only** | live alerts reviewed by staff |
| **M5: Punishments** | false-positive rate holds on real traffic |
