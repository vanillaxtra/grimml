# Changelog

All notable changes to GrimML (the detector plugin), grimmlrecorder and the published models.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the plugins use
[Semantic Versioning](https://semver.org/). Model versions (`models/manifest.json`) are counted separately and reach
servers through auto-update, so they are listed under the plugin release they shipped with.

## [Unreleased]

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

[Unreleased]: https://github.com/vanillaxtra/grimml/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/vanillaxtra/grimml/releases/tag/v1.1.0
