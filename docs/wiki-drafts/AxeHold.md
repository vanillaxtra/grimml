> **Off by default.** Strong legit players match this pattern in real duels. Read "Known false flags" before turning it on.

### How the cheat works

Auto Shield Breaker watches for an opponent blocking with a shield. It swaps to your axe, hits the shield to disable it, and swaps straight back to your weapon so your next hit lands.

### What GrimML measures

An **axe hold** is a swap to the axe, at least one hit on a player, then the swap away. AxeHold counts, in client ticks, how soon the swap away comes after the hit. It alerts when `count` (6) of the last `of` (10) axe holds swap away exactly one tick after the hit.

### Why legit players do not flag

Against a shielding bot, a hand swapped away 2 or more ticks after the hit on 92–97% of fast stuns (3 sessions, about 220 axe holds, 0 alerts). Prestige Auto Shield Breaker swapped away 1 tick after on 63–98%, at every setting (6 sessions, 6–17 alerts each).

**But:** in real duels, strong players swapped away one tick after the hit on 86–95% of stuns, the same as the cheat. They were flagged 6–9 times per fight in 3 fights. That's why the check is off by default.

### Known false flags and limits

- Flags strong legit players in real duels (see above).
- Grim's shield breaker swaps back after 2 ticks, like a fast hand, and is not caught.
- **Needs 1.21.2+ clients** (client ticks).

### Tuning for your server

```yaml
axe-hold:
  enabled: false
  count: 6
  of: 10
```

If you turn it on, use it for alerts only and leave the `shield-breaker` punishment group off. It shares `lunge-rate.alert-cooldown-ms`. `grimml.bypass.axehold` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Shield Breaker (80%) x1
Details: axe swapped away one tick after the hit on 80% of the last 10 axe hits
```

### Notes and history

- 1.1.0: released off by default after the real-duel results above.
