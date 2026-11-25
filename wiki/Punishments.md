# ⚖️ Punishments

Run any console commands when a player reaches a number of violations. **Off by default.**

```yaml
punishments:
  enabled: true
  groups:
    instant-lunge:
      checks: [Instant Lunge, Swap Macro]
      at-violations:
        3:
          - "kick %player% Unfair advantage (%check%)"
        6:
          - "ban %player% Unfair advantage (%check%)"
```

* **One violation** = one flagged fight (ML checks) or one alert (rule checks).
* **Groups** – each group adds up its checks' violations (internal names, [short names](Checks#short-names) or `*`).
* **Decay** – `decay-per-minute` per group (0.05 = one violation per 20 min). **Relogging does not reset them.**
* **Webhook** – every punishment is posted to Discord.
* `/grimml status <player>` shows a player's counts.

---

# 📖 In-depth explanation

> ✏️ **Write-up space.** Everything above is the quick reference. Fill in the sections below with the full story of
> this feature; delete any heading you do not need. HTML comments (`<!-- -->`) are hidden on the rendered page.

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
