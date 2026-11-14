# GrimML detector

Live ML combat checks that run next to [Grim Anticheat](https://github.com/GrimAnticheat/Grim). It scores every
player in a fight with the models trained in `../training` and reports flags the way Grim does.

> **Experimental.** The current models were trained on one cheat operator using one client. Use the alerts to review
> players; keep `punishments.enabled: false` until the models are trained on much more data.

## Install

1. `mvn clean package` in the repo root, then copy `detector/target/grimml-detector-0.1.0-SNAPSHOT.jar` to `plugins/`.
2. Needs **GrimAC** and **PacketEvents** (the same ones Grim uses), Paper 1.21+, Java 21.
3. Start the server. `plugins/GrimMLDetector/config.yml` is created; the defaults are fine.

The recorder (`recorder/`) is not needed on servers that only detect.

## What it looks like

Flags use Grim's own `alerts-format` and prefix and go to everyone with `/grim alerts` on, for example
`Grim » Steve failed MLKillAura (x1) 7/24 windows (29%, line 20%) avg 0.71`.

- Grim's `FlagEvent` is fired for every flag, so other Grim add-ons see it and can cancel it.
- Discord: posts to Grim's webhook url (or `alerts.webhook-url`) in Grim's embed style.
- Punishments: console commands at violation counts in `config.yml` (off by default). One violation = one flagged fight.
- Grim's API (1.3.x) has no way to add a third-party check to Grim's own check list, so `/grim` commands, Grim's
  `punishments.yml` and Grim's violation history do not know about these checks. Everything else above works.

| Command | |
|---|---|
| `/gmld status` | loaded models, thresholds, windows scored, last scan time |
| `/gmld update` | check for new models now |
| `/gmld reload` | reload config.yml |

Permissions: `grimml.detect.admin` (op), `grimml.detect.bypass` (never scanned). Creative/spectator and
Floodgate (Bedrock) players are skipped.

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

To publish new models after recording more data:

```bash
cd training
python prestige_quick.py --match-activity
python publish_models.py out_quick/<run> --enable "Kill Aura,Silent Aim"
python export_parity.py
cd .. && mvn clean package
```

Then commit and push `models/` (and the fixture). Servers pick it up on their next check or `/gmld update`.
`publish_models.py` only switches on the modules you list; the rest are published switched off.

The repo is public, so anyone can read the published models and thresholds.
