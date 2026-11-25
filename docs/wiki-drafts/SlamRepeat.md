> **Off by default** (`combo-repeat.slam: false`). Not calibrated yet.

### How the cheat works

A stun slam is: hit with the axe to disable the shield, swap to the mace, and land a mace hit (usually a falling smash). Auto Stun Slam and slam macros do this with fixed delays, so every slam is timed the same.

### What GrimML measures

Each stun slam is written down as the number of client ticks between its steps (axe swapped in, axe hit, swap to the mace, mace hit). The check alerts when `count` (8) of the last `of` (10) stun slams have exactly the same timing.

### Why legit players do not flag

Untested. Stun slams are chained at the same fastest-possible tick timing as stuns, and [StunRepeat](StunRepeat) showed strong players hit that floor every time. There are no usable recordings of stun slams yet, legit or cheating, so the check has not been calibrated.

### Known false flags and limits

- Not calibrated. Expect the same problem as StunRepeat with strong players.
- **Needs 1.21.2+ clients** (client ticks).
- A macro with random delays avoids it.

### Tuning for your server

```yaml
combo-repeat:
  slam: false
  count: 8
  of: 10
```

Keep it off unless you are testing it. If you turn it on, use it for alerts only. `grimml.bypass.slamrepeat` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Stun Slam (80%) x1
Details: 80% of the last 10 stun slams identical to the tick: swap-in 0t, swap away 1t, mace hit 3t
```

### Notes and history

- 1.1.0: released off by default and not calibrated. Recordings of stun slams (legit and Auto Stun Slam) are needed.
