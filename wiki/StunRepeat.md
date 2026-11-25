# 💫 StunRepeat

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-off-lightgrey.svg) ![clients](https://img.shields.io/badge/clients-1.21.2%2B-orange.svg)

**Axe stuns (swap in, axe hit, swap away, follow-up hit) repeated with exactly the same tick timing.**

<!-- GIF: stunrepeat - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `StunRepeat` |
| **Type** | Rule check (no model, exact) |
| **Default** | **Off** |
| **Clients** | 1.21.2+ (client ticks) |
| **Bypass** | `grimml.bypass.stunrepeat` |
| **Config section** | `combo-repeat` |

---

# 🔍 What It Detects

* Each stun is written down as its timing signature: swap-in → hit, hit → swap away, swap away → follow-up.
* Alerts when `count` of the last `of` stuns share one signature.
* **Off by default:** strong players land on the same 0–1 tick floor as the cheat.

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Strong legit same pattern | 86–95% |
| Prestige Auto Shield Breaker | 95–98% |
| Grim Kill Aura stun | 67–79% |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
combo-repeat:
  stun: false
  count: 8
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
