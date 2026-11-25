> **Off by default** (`combo-repeat.stun: false`). It flags strong legit players.

### How the cheat works

A stun is: swap to the axe, hit to disable the shield, swap to your main weapon, hit again. Stun macros and Auto Shield Breaker do this chain with fixed delays, so every stun is timed the same.

### What GrimML measures

Each stun is written down as the number of client ticks between its steps:

- axe swapped in → axe hit
- axe hit → swap away
- swap away → follow-up hit

A hand never does the same chain to the exact tick every time, but a macro with fixed delays does. The check alerts when `count` (8) of the last `of` (10) stuns have exactly the same timing.

### Why legit players do not flag

The idea is that human timing varies by a tick or two from stun to stun. For stuns this turned out **not** to hold. Strong players swap and hit in the same tick and swap away on the next on 86–95% of stuns. That's the fastest possible chain, and it's exactly what Prestige Auto Shield Breaker does (95–98%). Both sit at the floor of 0–1 tick, so they match.

### Known false flags and limits

- Flags strong legit players (see above). That's why it's off.
- **Needs 1.21.2+ clients** (client ticks).
- A macro with random delays avoids it.

### Tuning for your server

```yaml
combo-repeat:
  stun: false
  count: 8   # shared by StunRepeat, SlamRepeat and WebRepeat
  of: 10
```

If you turn it on, use it for alerts only. It shares `lunge-rate.alert-cooldown-ms`. `grimml.bypass.stunrepeat` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Stun Macro (90%) x1
Details: 90% of the last 10 stuns identical to the tick: swap-in 0t, swap away 1t, follow-up hit 0t
```

### Notes and history

- 1.1.0: released off by default.
