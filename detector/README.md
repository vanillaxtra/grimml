# GrimML

Machine-learning combat checks that run next to [Grim Anticheat](https://github.com/GrimAnticheat/Grim). It scores
every player in a fight with the models trained in `../training` and alerts staff.

> **Beta.** The models are trained on a small number of players. Use the alerts to review players; keep
> `punishments.enabled: false` until you have checked the alerts on your own server.

## Install

1. Build with `mvn clean package` in the repo root, then copy `detector/target/GrimML-1.1.0.jar` to `plugins/`.
2. Needs **GrimAC**, Paper 1.21+ and Java 21.
3. Start the server. `plugins/GrimML/config.yml` is created; the defaults are fine.

The recorder (`recorder/`, plugin `grimmlrecorder`) only makes training data and is not needed to detect.

> **Upgrading from the old GrimMLDetector / grimml plugins:** delete or rename the old `plugins/grimml/` folder
> before installing. On Windows, `plugins/GrimML/` and `plugins/grimml/` are the same folder.

## What it looks like

One short line per flag, with a confidence anyone can read:

`GrimML » Steve flagged Kill Aura (74%) x2`

Hovering shows the details (check, description, violations, what was measured, ping, client), and clicking
teleports you to the player (`alerts.click-command`). Everything is configurable in the `alerts` section.

**Confidence** is the model's average cheat score over the fight for ML checks, and for rule checks the share of
the player's recent swaps or hits that matched the cheat's pattern.

- Grim's `FlagEvent` is fired for every flag, so other Grim add-ons see it and can cancel it.
- Discord: the embed is laid out exactly like Grim's own webhook (same fields, colour, player-head thumbnail, Verbose
  field, footer and timestamp). It posts to Grim's webhook url unless `webhook.url` is set.
- Punishments (off by default): `punishments.groups` in `config.yml`. Each group lists checks (check names, short
  names such as `Instant Lunge`, or `*`) and console commands to run at violation counts, e.g. kick at 3 and ban at 6.
  A group adds up the violations of all its checks, which decay over time and survive relogging. One violation = one
  flagged fight (ML checks) or one alert (rule checks). Every punishment also posts a red "GrimML Punishment" embed to
  the webhook with the commands that ran (`webhook.punishments`). `/grimml status <player>` shows their counts.
  The old flat `punishments.at-violations` still works, counted over every check together.
- Grim's API (1.3.x) has no way to add a third-party check to Grim's own check list, so `/grim` commands, Grim's
  `punishments.yml` and Grim's violation history do not know about these checks.

| Command | Permission | |
|---|---|---|
| `/grimml alerts` | `grimml.alerts` | turn your alerts on or off |
| `/grimml status [player]` | `grimml.admin` | checks, models, and whether a player is scanned |
| `/grimml update` | `grimml.admin` | download newer models now |
| `/grimml reload` | `grimml.admin` | reload config.yml |

`/gml` is a short alias. Every option tab-completes.

| Permission | Default | |
|---|---|---|
| `grimml.*` | op | everything below except bypass |
| `grimml.alerts` | op | see alerts in chat |
| `grimml.alerts.sound` | op | hear a ping with every alert (`alerts.sound`) |
| `grimml.notify` | op | be told when new models are downloaded |
| `grimml.update.notify` | op | be told on join (once a day) when a new GrimML release is on GitHub (`update-checker`) |
| `grimml.admin` | op | status, reload, update |
| `grimml.bypass` | nobody | never scanned by any check |
| `grimml.bypass.<check>` | nobody | never flagged by one check: `mlkillaura`, `mlaimassist`, `mltriggerbot`, `mlsilentaim`, `aimgrid`, `lungerate`, `lungehold`, `swaporder`, `axehold` (`grimml.bypass.*` = all) |

Creative/spectator and Floodgate (Bedrock) players are skipped.

## How it decides

1. **Netty threads** copy rotation, position, on-ground and attack times into a small per-player ring, the same way the
   recorder builds its frames (one frame per server tick).
2. **One background thread** wakes every 2 s. For each player who attacked in the last 8 s it takes the window
   (last 160 frames + attacks), computes the 13 features and scores every enabled model. Nothing runs on the main thread
   except a tick counter and sending chat.
3. A window counts as flagged when its score passes the model's window threshold (5% of legit windows).
4. A **fight** is flagged when at least 10 windows were scored and the flagged share reaches `alert_share`, which is set
   above every legit fight seen in testing. Then the count restarts, so a long fight can flag again.

The Java feature code and model reader are checked against Python on real windows every build
(`ParitySelfCheck`: features within 1e-9, scores identical).

## Updating the models

Models live in `../models` and are bundled in the jar. The plugin also checks
`models.manifest-url` (this repo's `models/manifest.json` on GitHub) every 15 minutes, downloads a newer version,
verifies every file's sha256 and switches over without a restart. A broken download keeps the old models running.

To publish a new model after recording more data (one module at a time, see `training/README.md`):

```bash
cd training
grimml-train train "Silent Aim"
grimml-train publish runs/silent_aim/<time> --enable
grimml-train parity
cd .. && mvn clean package
```

Then commit and push `models/` (and the fixture). Servers pick it up on their next check or `/grimml update`.
`publish` only replaces the module it was given; the other modules stay as they are.

The repo is public, so anyone can read the published models and thresholds.

## AimGrid check (no model needed)

Real mouse rotation is a whole number of sensitivity steps (`(sens*0.6+0.2)^3 * 8 * 0.15` degrees, yaw and pitch).
`AimGrid` learns each player's step from their own rotation (outside fights too), then runs a sequential test
(CUSUM) on off-grid rotation while they fight. It alerts when the score reaches `aim-grid.alert-score` and the
off-grid rotation leans toward the opponent, so zoom, cinematic camera and unusual mice (off-grid both ways)
do not flag. Players whose rotation fits no grid (controllers, cinematic camera) are skipped. Settings: the
`aim-grid` section of `config.yml`. `/grimml status <player>` shows a player's learned step and current score.

Calibrated on recorded fights (training `aimgrid.py`, same code): legit fights never scored above 2.3; aim
assist sessions reached 20 within 1.5-6 s once the step was known. The Java port is checked against Python on
every build (`live_grid.txt`). The same grid measurement feeds the ML models as `grid_off_frac`,
`grid_off_toward`, `grid_off_away`, `grid_toward_minus_away`.

## Swap checks: LungeRate, LungeHold, SwapOrder, AxeHold (no model needed)

Instant Lunge swaps to the spear, jabs and swaps back as one macro. A swap-lunge is a spear jab (a digging STAB
packet, not an attack) within 150 ms of a hotbar swap to the spear; each swap counts once, and jabbing while holding
the spear never counts. The three checks measure what a hand or the vanilla client cannot do, not how one cheat
client does it, so they apply to clients never recorded. They run on every player, not only those who attacked,
because a lunge-only player never sends an attack. One `LungeRate` object per player feeds all three
(`features/LungeRate.java`).

| check | flags | default | legit (recorded) | Instant Lunge (recorded) |
|---|---|---|---|---|
| `LungeRate` | gaps between swap-lunges in [`min-ms`, `fast-ms`) | 6 within 30 s, 75-300 ms | fastest gap 345 ms (3 sessions, 2 players), 0 alerts | 100-250 ms apart, alerted in all 7 sessions |
| `LungeHold` | spear held exactly one client tick | 5 of the last 10 swap-lunges | 2 of 182 (1.1%), 0 alerts | 0-0: 100%, 20-70: 63%, 30-90: 34% / 0% |
| `SwapOrder` | a hotbar swap after an attack or STAB in the same client tick (vanilla sends hotbar changes first) | 2 within 60 s | 0 in ~1400 hits and swaps | rare for Prestige; any client doing the chain inside one tick does it every lunge |
| `AxeHold` | swap away from the axe one client tick after the last axe hit (Auto Shield Breaker) | 6 of the last 10 axe holds | 3-8% of ~220 fast hand stuns on a shielding bot, 0 alerts | Prestige 63-98% (6 sessions, 6-17 alerts each); Grim (2 ticks) not caught |

LungeHold, SwapOrder and AxeHold need client ticks (1.21.2+ clients); older clients are skipped by those. Settings:
the `lunge-rate`, `lunge-hold`, `swap-order` and `axe-hold` sections of `config.yml`. Calibrated with training `spearmace.LiveLunge`
(same code) replayed over every recorded session; the Java port is checked against Python on every build
(`live_lunge.txt`).

## Update checker and bStats

On start, and when someone with `grimml.update.notify` joins (re-checked at most hourly), GrimML asks GitHub for the
latest release of `vanillaxtra/grimml` off the main thread. A newer tag prints a console banner and gives those
players a clickable download link once a day. `update-checker: false` turns it off.

bStats id [35441](https://bstats.org/plugin/bukkit/grim-ml/35441), shaded and relocated to
`dev.grimml.detect.libs.bstats`. Custom charts: models version, punishments on/off, webhook on/off. Opt out in
`plugins/bStats/config.yml`.

## License

Code: MIT (`../LICENSE`). Models: [GrimML Model License](../models/LICENSE.md): run them only through this plugin; no
paid use, no redistribution, no derived models.
