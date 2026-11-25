### How the cheat works

Instant Lunge (Prestige, Grim and others) turns a spear lunge into one key press. It swaps to the spear, jabs, and swaps back to your weapon in one macro, with a fixed or random delay of a few tens of milliseconds. You get the spear's lunge without the time it takes to swap by hand.

### What GrimML measures

A **swap-lunge** is a spear jab within 150 ms of a hotbar swap to the spear. LungeRate measures the gap between one swap-lunge and the next. A gap under `fast-ms` (300 ms) is "fast". Gaps under `min-ms` (75 ms) are ignored, because lag bunches packets together. The check alerts when `count` (6) fast gaps happen within `window-ms` (30 s).

### Why legit players do not flag

Doing swap → jab → swap back by hand takes time. About 700 recorded legit swap-lunges were never under 345 ms apart. That includes a spear-mace duel and a session where the tester tried to go as fast as possible. Instant Lunge at delays of 0–0, 20–70 and 30–90 ms chained them 100–250 ms apart and alerted in every session. Holding the spear and jabbing without swapping never counts, so players who never swap-lunge can't flag.

### Known false flags and limits

- No legit player flagged in testing. The gap between legit (≥345 ms) and the cutoff (300 ms) is small, so a very fast player on a high-latency connection is the most likely false flag. UNVERIFIED live.
- An Instant Lunge set to a long delay (over 300 ms between lunges) would avoid this check. [LungeHold](LungeHold) and [SwapOrder](SwapOrder) look at other parts of the same macro.
- Works on any client version (it uses server receive times, not client ticks).

### Tuning for your server

```yaml
lunge-rate:
  enabled: true
  fast-ms: 300             # lower = only very fast chains count
  min-ms: 75
  count: 6
  window-ms: 30000
  alert-cooldown-ms: 15000 # shared by lunge-rate, lunge-hold, swap-order, axe-hold and the repeat checks
```

LungeRate and LungeHold together are "Instant Lunge" in `punishments`. The default `instant-lunge` group kicks at 3 violations and bans at 6. `grimml.bypass.lungerate` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Instant Lunge (100%) x1
Details: 6 spear swap-lunges under 300 ms apart within 30 s
```

### Notes and history

- 1.1.0: first release.
