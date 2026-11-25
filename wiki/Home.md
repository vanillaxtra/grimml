# GrimML Wiki

[![Version](https://img.shields.io/badge/version-1.1.0-blue.svg)](https://github.com/vanillaxtra/grimml/releases)
[![Platform](https://img.shields.io/badge/Paper-1.21%2B-purple.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)
[![Requires Grim](https://img.shields.io/badge/requires-GrimAC-00bcd4.svg)](https://github.com/GrimAnticheat/Grim)
[![Status](https://img.shields.io/badge/status-beta-yellow.svg)](https://github.com/vanillaxtra/grimml#beta-read-this)

Machine-learning combat checks for **[Grim Anticheat](https://github.com/GrimAnticheat/Grim)**. Every check has its own
page with a quick reference and a full in-depth explanation.

# 🧠 Checks

**ML checks:** [Kill Aura](Kill-Aura) · [Aim Assist (ML)](Aim-Assist) · [Triggerbot](Triggerbot) · [Silent Aim](Silent-Aim)

**Rule checks:** [Aim Assist (AimGrid)](AimGrid) · [Instant Lunge (LungeRate)](LungeRate) · [Instant Lunge (LungeHold)](LungeHold) · [Swap Macro (SwapOrder)](SwapOrder) · [Shield Breaker (AxeHold)](AxeHold) · [StunRepeat](StunRepeat) · [SlamRepeat](SlamRepeat) · [WebRepeat](WebRepeat)

➡️ [All checks in one table](Checks)

---

# 📚 Features

| Page | What |
|------|------|
| [⚙️ How Scoring Works](How-Scoring-Works) | From packets to a flagged fight |
| [🔔 Alerts](Alerts) | Chat line, hover, click, sound |
| [💬 Discord Webhook](Discord-Webhook) | Grim-style embeds for alerts and punishments |
| [⚖️ Punishments](Punishments) | Commands at violation counts |
| [🔄 Updates](Updates) | Model auto-update and plugin update checker |

---

# 🧾 Reference

* [Commands and Permissions](Commands-and-Permissions) (with placeholders)
* [Configuration](Configuration)

---

## 🚀 Install

1. Download the jar from [Releases](https://github.com/vanillaxtra/grimml/releases) and put it in `plugins/`.
2. Needs **GrimAC**, Paper 1.21+ and Java 21.
3. Start the server. `plugins/GrimML/config.yml` is created; the defaults are fine.

> **Beta.** The models are trained on a small number of players, so they can flag legit players. Keep punishments for
> the ML checks off (or high) until the alerts look right on your server.
