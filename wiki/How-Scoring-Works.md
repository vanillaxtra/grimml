# ⚙️ How Scoring Works

**How the ML checks go from packets to a flagged fight.**

1. **Netty threads** copy rotation, position, on-ground and attack times into a small per-player ring, one frame per
   server tick.
2. **One background thread** wakes every 2 s. For each player who attacked in the last 8 s it takes the window
   (last 160 frames + attacks), computes the features and scores every enabled model.
3. A **window** is flagged when its score passes the model's window threshold (5% of legit windows).
4. A **fight** is judged once per block of 120 windows (4 min) and once when it ends (20 s without a scored window).
   It flags when that stretch has at least 20 windows and its flagged share reaches `alert_share`.
5. **Early alert:** after 10 windows (~20 s) a fight flags at once if the low end of its share's confidence
   interval (Wilson, z = 3) already reaches `alert_share`. Most cheaters flag in 20–30 s.

* Nothing runs on the main thread except a tick counter and sending chat.
* Java features and model reader are checked against Python on every build (`ParitySelfCheck`).

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
