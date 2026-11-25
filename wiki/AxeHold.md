# 🪓 Shield Breaker (AxeHold)

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-off-lightgrey.svg) ![clients](https://img.shields.io/badge/clients-1.21.2%2B-orange.svg)

**Swapping away from the axe exactly one tick after breaking a shield, every time.**

<!-- GIF: axehold - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `AxeHold` |
| **Type** | Rule check (no model, exact) |
| **Default** | **Off** |
| **Clients** | 1.21.2+ (client ticks) |
| **Bypass** | `grimml.bypass.axehold` |
| **Config section** | `axe-hold` |

---

# 🔍 What It Detects

* Targets Auto Shield Breaker (swap to axe, hit, swap away the next client tick).
* Grim's shield breaker swaps back after 2 ticks, like a fast hand, and is not caught.
* **Off by default:** strong players in real duels swap away one tick after the hit on 86–95% of stuns.

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Legit vs shielding bot | 3–8% |
| Strong legit in duels | 86–95% |
| Prestige Auto Shield Breaker | 63–98% |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
axe-hold:
  enabled: false
  count: 6
  of: 10
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
