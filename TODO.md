# grimml: what's left

**Updated 2026-11-18.** Code on `main`: recorder (schema 4), detector **1.1.0** (4 ML checks + 4 rule checks, alerts,
webhook, punishments, model auto-update, update checker, bStats), `training/` (`grimml-train`, one module at a time),
models v9.

Legend: ⬜ todo · 🔶 partial / needs verifying · 👤 you (servers, accounts, process), not code

---

## 🚀 Before releasing 1.1.0

### Blockers: the release looks broken or untrustworthy without these

- [ ] ⬜ **False flags: judge each fight once.** `Scanner` re-tests `share >= alertShare` after every window from window
  10 on (`detector/.../scan/Scanner.java:228`), but training measures the share once per whole fight. Re-testing on
  every window keeps rolling the dice, so long legit fights eventually cross the line (28 live alerts on 5 legit
  players). Fix: decide at fight end or in fixed ~30-window blocks, minimum ~20 windows. Then retrain and republish.
- [ ] 👤 **Record the falsely flagged players as legit** on that server and add them to `training/config/sessions.csv`
  (different server, Lunar/Fabric 1.21.11: the training data has none of that).
- [ ] 👤 Spectate the player flagged AimAssist 98% + AimGrid before treating that one as a false flag.
- [x] **Take `CHEATTRAINING.md` out of the public repo.** Untracked and git-ignored; the local copy stays.
- [ ] 👤 **Remove it from git history too** (old commits still contain the names):
  `git filter-repo --path CHEATTRAINING.md --invert-paths`, then force-push. This rewrites every commit hash, so
  re-clone everywhere afterwards.
- [ ] ⬜ **README images.** The README links `assets/hero.gif`, `killaura.gif`, `aimassist.gif`, `lunge.gif`,
  `shieldbreaker.gif`, `alerts.gif`, `webhook.png`, but `assets/` doesn't exist. 👤 record the GIFs, or hide those
  image lines until you have them.
- [x] **Fix outdated docs:**
  - [x] `CONTRIBUTING.md`: "Never punish" (punishments exist now), models "CC BY-NC-SA" (now the GrimML Model
    License), no real player names in commits
  - [x] `SECURITY.md`: "no release builds yet"
  - [x] `LICENSE`: copyright holder is now "the GrimML authors" (was "stuff")
  - [x] root `.gitignore`: `publish_models.py` / `training/out*` comments
  - [x] `docs/decisions.md`: old `training/out/ingest` line
- [ ] ⬜ **Test boot on a real server** (none done for 1.1.0). Run Paper 1.21.x + Grim 2.3+ in
  `servers/claude/grimml` and check:
  - [ ] it starts and the console shows no errors
  - [ ] bStats loads (relocated)
  - [ ] the update checker stays quiet before a release exists and shows a notice after one
  - [ ] `/grimml status|reload|update|alerts`
  - [ ] a punishment group fires its commands and posts the red webhook embed
  - [ ] it also starts on 26.x (CONTRIBUTING promises 1.21.1 and 26.x)
- [x] **Beta notice** in the console on every start: use ML alerts to decide who to watch, keep their punishments off.
- [ ] 👤 **Decide the default ML state.** All 4 ML checks are switched on in models v9 (alerts only;
  `punishments.enabled: false` by default). Keep them on, or publish them off and let owners opt in.
- [x] **Detector `config-version`** (1), with a startup warning for older files and a build check that the shipped
  config matches.
- [ ] 👤 **Create the GitHub release `v1.1.0`.**
  - [ ] attach `GrimML-1.1.0.jar` (and the recorder jar if you ship it)
  - [ ] delete the stale `v0.1.0-SNAPSHOT` tag
  - [ ] the README download link and the update checker both need this

### Should do: what makes it look like a maintained project

- [x] **CI:** `.github/workflows/build.yml` running `mvn -B package` + `pytest` on every push and pull request,
  plus a build badge in the README.
- [x] **Release workflow:** pushing a `v*` tag builds the jars and attaches them to the GitHub release.
- [x] **CHANGELOG.md** (Keep a Changelog), starting with 1.1.0: punishments, update checker, bStats, new
  license, training CLI.
- [ ] ⬜ **Model updates come straight from `main`**, so one bad push reaches every server. Serve
  `models/manifest.json` from a release branch or a release asset, and ideally sign the manifest.
- [x] Recorder's 14 `*SelfCheck` classes moved to `src/test` (no longer in the jar); 2 that never ran now run.
- [ ] 👤 GitHub settings:
  - [ ] repo description and topics (`minecraft`, `anticheat`, `paper-plugin`, `machine-learning`, `grimac`)
  - [ ] social preview image
  - [ ] protect `main`
- [ ] 👤 List on **Modrinth / Hangar / SpigotMC** with the README text and GIFs.
- [ ] 👤 Commit messages: follow CONTRIBUTING (`area: message`). Avoid strings of "Update CHEATTRAINING.md".

### Later: engineering polish

- [ ] ⬜ JUnit 5 instead of `main()` self-checks via exec-maven-plugin (normal test reports, IDE support)
- [ ] ⬜ Maven Wrapper (`mvnw`)
- [ ] ⬜ Parent POM `pluginManagement` / `dependencyManagement` (versions are repeated in both modules)
- [ ] ⬜ Formatting and linting: Spotless or Checkstyle (Java), `ruff` + `mypy` (training), `.editorconfig`
- [ ] ⬜ Dependabot for Maven, pip, Actions
- [ ] ⬜ Split `GrimMLDetector.java` (647 lines: config parsing, commands, metrics in one class)
- [ ] ⬜ Detector MSPT and scan-latency numbers in `/grimml status` (one background scanner thread; show it keeps up)
- [ ] ⬜ Move `ACCESSIBILITY.md` to `docs/` (it is mostly about the recorder)

---

## 🧠 Models and training data

- [ ] 👤 More **legit** data from other servers, clients (Lunar, Fabric, Badlion, Feather) and versions. The
  biggest cause of false flags is legit play unlike the training data.
- [ ] 👤 Hard legit negatives: top PvPers, high-DPI, controller, high ping
- [ ] 👤 More cheat operators and clients (only one operator so far). Hold one client out for `grimml-train loco`.
- [ ] ⬜ Modules worth training next: **Auto Shield Breaker**, **Auto W Tap**, **Autoclicker** (entries exist in
  `training/config/modules.yaml`; they need sessions)
- [ ] ⬜ `sessions.csv` modules without a `modules.yaml` entry: Auto Breach Swap, Auto Diagonal Pearl Catch, Auto Pearl
  Catch, Auto Wind Charge Hitter, Auto Xbow Cart, Wind Charge Aura
- [ ] ⬜ Silent Aim schema 4 run separates perfectly (AUC 0.99) but the shortcut test says LEAK RISK (0.83): record
  schema 4 legit with the same setup before using it
- [ ] 👤 Set `validity_fixed_from` in `training/config/labels.yaml` once the fixed recorder is live
- [ ] ⬜ Monthly retrain and drift check (alert rates per check per week)

---

## 📼 Recorder

- [ ] 🔶 Armor/hotbar **enchants in binary** (tracked in `InventoryMirror`, not exported per window)
- [ ] 🔶 Mace/spear **rich** records (smash, charge/lunge detail, wind-charge timing)
- [ ] 🔶 Retotem latency / click-failure detail
- [ ] 🔶 Jump-reset window (−5..+10 ticks around KB): knockback trigger only
- [ ] ⬜ PROJECTILE record type defined but not emitted
- [ ] ⬜ Map files per map id (for offline line-of-sight)
- [ ] 🔶 Per-record-type drop counters in `/grimml status` (only windows + torn frames today)
- [ ] 🔶 Hourly file rotation / daily offload (date prefix only)
- [ ] 🔶 Round-trip tests for every record type
- [ ] ⬜ `/grimml mode markonly on|off` at runtime (today: config + reload)
- [ ] ⬜ Action bar visible to the staff operator when the mark target is someone else
- [ ] ⬜ Folia (non-goal for now)

---

## 👤 Process

- [ ] Privacy policy update on servers that record behaviour
- [ ] Legit roster (staff, opted-in, trusted players); `LEGIT_CONFIRMED` only during legit marks
- [ ] Test arena that mirrors the live server (same Grim, kit, map); clients only from official sources, in a VM
- [ ] Collection schedule: blatant/moderate/subtle settings, legit and cheat interleaved, re-collect after client
  updates
- [ ] 🔶 Calibrate `grimml-synth` from real sessions (tooling exists, needs data)
- [ ] Punish on ML checks only once the false-flag rate holds on real traffic (start with kicks, Grim combos)

---

## Milestones

| Milestone | Status |
|---|---|
| M1: Recorder live | ✅ deployed, perf OK |
| M2: Labels working | ✅ marks + sessions.csv; 🔶 one operator, little outside legit data |
| M3: First models | ✅ 4 ML checks published (models v9) |
| M4: Alert-only live | 🔶 running, false flags on unseen players (blocker above) |
| M5: Punishments | ⬜ feature built, off by default; switch on for ML checks only after M4 holds |
