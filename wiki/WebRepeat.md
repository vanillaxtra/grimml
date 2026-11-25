# 🕸️ WebRepeat

![type](https://img.shields.io/badge/type-rule%20check-00bcd4.svg) ![default](https://img.shields.io/badge/default-on-brightgreen.svg) ![clients](https://img.shields.io/badge/clients-1.21.2%2B-orange.svg)

**Stun webs (axe hit, swap to cobwebs, cobweb placed within 1 s) repeated with the same tick timing.**

<!-- GIF: webrepeat - add a clip of this check flagging -->

---

# 📋 At a Glance

| | |
|---|---|
| **Check name** | `WebRepeat` |
| **Type** | Rule check (no model, exact) |
| **Default** | On |
| **Clients** | 1.21.2+ (client ticks) |
| **Bypass** | `grimml.bypass.webrepeat` |
| **Config section** | `combo-repeat` |

---

# 🔍 What It Detects

* Signature: swap-in (or none), hit → cobweb swap, cobweb swap → place.
* Never repeated by hand: at most 43% of one player's webs had the same timing.
* No cheat recordings yet, so cheat detection is UNVERIFIED.

---

# 📊 Tested Against

| Measure | Value |
|---------|-------|
| Legit max identical | 43% |
| Legit alerts | 0 (~15 sessions) |

<sub>Source: recorded fights replayed. Numbers change as models and data are updated.</sub>

---

# ⚙️ Configuration

```yaml
combo-repeat:
  web: true
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
