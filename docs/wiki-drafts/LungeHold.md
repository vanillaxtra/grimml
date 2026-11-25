### How the cheat works

See [Instant Lunge (LungeRate)](LungeRate). The macro swaps to the spear, jabs and swaps back, usually faster than a hand can.

### What GrimML measures

How long the spear is held in each swap-lunge, counted in **client ticks**. Each tick the client sends a tick-end packet, so GrimML knows which tick every swap and jab happened in. A swap-lunge where the spear is held for exactly one client tick (swap in and jab on one tick, swap out on the next) is a "one-tick hold". The check alerts when `count` (5) of the last `of` (10) swap-lunges are one-tick holds.

This also catches a lunge macro that is chained slowly enough to pass LungeRate. Spacing the lunges apart doesn't change how long each one holds the spear.

### Why legit players do not flag

A hand rarely swaps in, jabs and swaps out within 50 ms: 2 of 182 swap-lunges in a recorded legit spear-mace fight. Instant Lunge did it on 34–100% of swap-lunges at 0–90 ms delay, however slowly the lunges were chained.

### Known false flags and limits

- **Needs 1.21.2+ clients.** Older clients don't send client ticks, so this check doesn't run for them.
- A macro with a delay longer than one tick between jab and swap back avoids it. Most "random delay" settings still land on one tick often enough.
- No legit alerts in testing.

### Tuning for your server

```yaml
lunge-hold:
  enabled: true
  count: 5   # one-tick holds ...
  of: 10     # ... among this many latest swap-lunges
```

It shares `lunge-rate.alert-cooldown-ms` and the "Instant Lunge" punishment group. `grimml.bypass.lungehold` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Instant Lunge (70%) x1
Details: spear held one tick on 70% of the last 10 swap-lunges
```

### Notes and history

- 1.1.0: first release.
