# 🔀 Swap Macro (SwapOrder)

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-on-brightgreen.svg) ![clients](https://img.shields.io/badge/clients-1.21.2%2B-orange.svg)

**A hotbar swap sent after an attack or spear jab in the same client tick.**

<!-- GIF: swaporder - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `SwapOrder` |
| **Type** | Rule check (no model, exact) |
| **Default** | On |
| **Clients** | 1.21.2+ (client ticks) |
| **Bypass** | `grimml.bypass.swaporder` |
| **Config section** | `swap-order` |

---

# 🔍 What It Detects

* The vanilla client always sends hotbar changes **before** attacks inside one tick.
* Any client that swaps, hits and swaps back inside one tick does it every time.
* Never seen by hand: 0 in ~1400 recorded legit hits and swaps.

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Legit matches | 0 / ~1400 |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
swap-order:
  enabled: true
  count: 2
  window-ms: 60000
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
