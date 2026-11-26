# Changelog

All notable changes to GrimML (the detector plugin), grimmlrecorder and the published models.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the plugins use
[Semantic Versioning](https://semver.org/). Model versions (`models/manifest.json`) are counted separately and reach
servers through auto-update, so they are listed under the plugin release they shipped with.

## [Unreleased]

## [1.2.0] - unreleased

**Update needed for models v11 and later.** The next Silent Aim model uses the new flick features, which 1.1.0 does
not compute. A 1.1.0 server that downloads it refuses the whole model set and keeps running the models it already has
(v10), so it also misses the Kill Aura, Aim Assist and Triggerbot updates.

### Added

- **Staff overlay** (`/grimml overlay`, `grimml.overlay`): a hologram riding on flagged players' heads, seen only by
  staff who turned it on. One line per check: red for alerts in the last `overlay.keep-minutes` (confidence and
  flagged/total fight windows, or `x<count>` for rule checks), yellow for a fight in progress with flagged windows.
  It exists only in the staff member's client (packets, no server entity), so it follows the head with no extra
  packets and never blocks teleports. New `overlay` section in `config.yml` (MiniMessage formats);
  `config-version` is now 2.
- **Alert history** (`/grimml history <player> [days]`, `grimml.history`): every alert and punishment is saved to
  `plugins/GrimML/history/<date>.jsonl` and kept for `history.keep-days` (14). Works for offline players and after
  restarts.
- **Missed alerts on join**: staff with `grimml.alerts` see the alerts and punishments from while they were away
  (at most `history.on-join.max-minutes` back), grouped per player and check; click a line for that player's history.
- **Punishment delay** (`punishments.delay-seconds`, default 10–45 s): each punishment waits a random time so a
  cheater can't tell which hit caught them. It is posted to the webhook when it runs.
- **Telemetry, opt-in** (`telemetry` section, off by default): uploads scored fight windows and alerts, anonymous
  (salted player hash, random server id; no names, UUIDs, IPs, chat or coordinates), every 60 minutes over HTTPS
  (port 443, so hosts that block other outgoing ports still reach it) to the GrimML receiver at
  `telemetry.notauthorised.lol`. `/grimml telemetry` to check and send now (a failed upload shows the network error),
  `grimml.bypass.telemetry` to leave a player out.
  Fights are sampled the same way on every server: every alerted and borderline fight, a matching number of quiet ones and
  a 3% random slice, each tagged with its band and keep chance. `/grimml label <player> cheat|legit [kind]`
  (`grimml.label`) and `grimml.telemetry.trusted` add labels for training. Each window also carries its attack and
  event counts, and a ban (the server's ban list, or a kick whose message mentions a ban) is sent as a `ban` line with
  the player's waiting fights kept; the kick message itself is never sent.
  Receiver in `tools/receiver` (Node, no dependencies, stops at 8 GiB). `config-version` is now 4.
- **Flick features** (`flick_one_tick_frac`, `flick_return_frac`, `flick_snap_frac`) for the ML checks: for every hit,
  how the view turned over the 3 client ticks before and after it. Silent aim, snap kill auras and knockback
  displacement turn onto the target on the hit tick and straight back; a real flick builds up over several ticks and
  keeps tracking. Needs 1.21.2+ clients (client ticks).

### Changed

- **AimGrid no longer flags non-linear mice** (Enhance pointer precision, Raw Accel, custom curves): each player's
  own off-grid rate outside fights sets their legit rate, fights are only tested once that baseline exists, players
  over 8% are skipped, and flicks over 20 steps per tick are ignored. New `aim-grid` settings: `baseline-margin`,
  `baseline-max`, `baseline-samples`, `max-steps`.

## [1.1.0] - unreleased

First public release.

### Added

- **ML checks** (LightGBM, scored off the main thread every 2 s on the last 8 s of a fight): Kill Aura, Aim Assist,
  Triggerbot, Silent Aim. A fight is flagged only when enough of its windows look like cheating.
- **Rule checks**: AimGrid (off-grid mouse rotation toward the opponent), Instant Lunge (`LungeRate`, `LungeHold`),
  Swap Macro (`SwapOrder`), Auto Shield Breaker (`AxeHold`), and combo repeat checks (`StunRepeat`, `SlamRepeat`,
  `WebRepeat`): the same stun, stun slam or stun web timed to the client tick again and again (macros).
  `AxeHold`, `StunRepeat` and `SlamRepeat` are off by default: strong players stun on the same tick pattern as the
  cheat.
- **Alerts** through Grim: one line with a confidence %, hover details, click to teleport, sound for
  `grimml.alerts.sound`, `/grimml alerts` toggle. Grim's `FlagEvent` is fired, so other Grim add-ons see GrimML flags.
- **Discord webhook** laid out like Grim's own, using Grim's webhook URL by default.
- **Punishments** (off by default): groups of checks with their own violation counts, decay and console commands;
  counts survive relogs; every punishment is posted to the webhook as a red embed; `/grimml status <player>` shows
  the counts.
- **Model auto-update**: new models are downloaded from this repository, checked with sha256 and switched in without
  a restart. A broken download keeps the old models running.
- **Update checker** for new GrimML releases on Modrinth and GitHub (console and `grimml.update.notify`, at most once a
  day). The download link is Modrinth's whenever Modrinth has the newest version.
- Staff with `grimml.notify` are told about a model update once each: at once if online, else when they next join.
- **bStats** (plugin id 35441): models version, punishments on/off, webhook on/off.
- `config-version` in `config.yml`, with a startup warning when the file is older than the plugin.
- `training/`: the `grimml-train` command (`train`, `loco`, `publish`, `parity`, `sessions`, `modules`), one module
  at a time, with labels and module settings in `training/config/`.
- CI: every push builds both plugins, runs the Java self-checks (including Java/Python feature and score parity) and
  the Python tests. Pushing a `v*` tag publishes a GitHub release with both jars.

### Changed

- ML checks judge a fight once per 4-minute block and once when it ends, instead of re-testing it after every
  window. Long legit fights no longer cross the alert line by chance (`scan.fight-block-windows`,
  `scan.fight-min-windows`).
- A fight that is clearly over the line is flagged early, after ~20 s of fighting, instead of waiting for the block
  to end (`scan.early-alert-z`, `scan.early-alert-min-windows`).
- Models are under the [GrimML Model License](models/LICENSE.md): free to run through the official plugin on any
  server, no paid use, redistribution or derived models. Models published before this (models v9 and earlier) keep
  the license they were published with.
- The recorder's self-checks moved to `src/test` and are no longer shipped inside the jar.

### Known issues

- The ML checks are in beta and have flagged legit players on servers unlike the training data (other clients,
  versions and play styles). Use their alerts to decide who to watch; keep punishments for them off.

[Unreleased]: https://github.com/vanillaxtra/grimml/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/vanillaxtra/grimml/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/vanillaxtra/grimml/releases/tag/v1.1.0
