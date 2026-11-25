# ⏱️ Instant Lunge (LungeHold)

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-on-brightgreen.svg) ![clients](https://img.shields.io/badge/clients-1.21.2%2B-orange.svg)

**The spear held for exactly one client tick: swap in, jab, swap back on the next tick.**

<!-- GIF: lungehold - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `LungeHold` |
| **Type** | Rule check (no model, exact) |
| **Default** | On |
| **Clients** | 1.21.2+ (client ticks) |
| **Bypass** | `grimml.bypass.lungehold` |
| **Config section** | `lunge-hold` |

---

# 🔍 What It Detects

* By hand this is rare (2 of 182 legit swap-lunges).
* Instant Lunge does it on 34–100% of lunges at 0–90 ms delay, however slowly they are chained.
* Shares the short name **Instant Lunge** with [LungeRate](LungeRate).

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Legit one-tick holds | 1.1% |
| Instant Lunge 0-0 / 20-70 / 30-90 | 100% / 63% / 34% |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
lunge-hold:
  enabled: true
  count: 5
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
