# 📐 Aim Assist (AimGrid)

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-on-brightgreen.svg) ![clients](https://img.shields.io/badge/clients-all-orange.svg)

**Mouse rotation that is not a whole number of sensitivity steps and leans toward the opponent.**

<!-- GIF: aimgrid - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `AimGrid` |
| **Type** | Rule check (no model, exact) |
| **Default** | On |
| **Clients** | All (mouse players) |
| **Bypass** | `grimml.bypass.aimgrid` |
| **Config section** | `aim-grid` |

---

# 🔍 What It Detects

* Real mouse rotation is a whole number of sensitivity steps: `(sens*0.6+0.2)^3 * 8 * 0.15` degrees.
* Learns each player's step from their own rotation, then runs a CUSUM test on off-grid rotation in fights.
* Only alerts when the off-grid rotation **leans toward the opponent** (zoom and cinematic camera are off-grid both ways).
* Per-player baseline outside fights keeps non-linear mice (Raw Accel, Enhance pointer precision) from flagging.
* Controllers and cinematic camera (no grid at all) are skipped.

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Legit max score | 2.3 |
| Aim assist reaches 20 in | 1.5–6 s |
| Legit fights alerting (replayed) | 2 / 30 |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
aim-grid:
  enabled: true
  legit-off-rate: 0.01
  cheat-off-rate: 0.10
  alert-score: 20.0
  min-toward-bias: 0.05
  alert-cooldown-ms: 15000
  baseline-margin: 1.5
  baseline-max: 0.08
  baseline-samples: 100
  max-steps: 20
```

---

# 📖 In-depth explanation

> ✏️ **Write-up space.** Everything above is the quick reference. Fill in the sections below with the full story of
> this check; delete any heading you do not need. HTML comments (`<!-- -->`) are hidden on the rendered page.

### 🎯 How the cheat works

<!-- What the cheat does on the client, which clients ship it, which settings matter (delays, FOV, smoothing...). -->

### 🔬 What GrimML measures

<!-- The signal: which packets, which features, which numbers. Diagrams / screenshots welcome. -->

### ✅ Why legit players do not flag

<!-- What separates a strong legit player from the cheat. Recorded legit data, edge cases you tested. -->

### ⚠️ Known false flags and limits

<!-- Situations that can flag a legit player, cheat settings that slip through, client versions skipped. -->

### 🛠️ Tuning for your server

<!-- Which config values to change for a stricter / looser server, and what each change costs. -->

### 🖼️ Example alerts

<!-- Paste a real alert line, hover text or webhook embed, plus a GIF if you have one. -->

<!-- IMAGE: example-<check> -->

### 📝 Notes and history

<!-- Changes between versions, ideas, things still UNVERIFIED. -->

---

[🏠 Home](Home) · [🧠 All Checks](Checks) · [⚙️ Configuration](Configuration)
