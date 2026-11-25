### How the cheat works

A stun web is: hit with the axe to disable the shield, swap to cobwebs, and place a web on the opponent to trap them. Macros do this with fixed delays, so every web is timed the same.

### What GrimML measures

Each stun web is written down as the number of client ticks between its steps (axe swapped in, axe hit, swap to cobwebs, cobweb placed). The check alerts when `count` (8) of the last `of` (10) stun webs have exactly the same timing.

### Why legit players do not flag

Placing a web means aiming at a block, not just clicking, so the timing varies from web to web by hand. In recorded legit fights, at most 43% of one player's webs had the same timing, well under the 80% needed. There were 0 legit alerts.

### Known false flags and limits

- Very few recorded stun webs, legit or cheating. The numbers above come from about 20 legit sessions and 8 cheat sessions across all combo types. UNVERIFIED on a live server.
- **Needs 1.21.2+ clients** (client ticks).
- A macro with random delays avoids it.

### Tuning for your server

```yaml
combo-repeat:
  web: true
  count: 8   # combos with exactly the same timing ...
  of: 10     # ... among this many latest stun webs
```

Raise `count` (for example to 9) if a legit web player ever flags. It shares `lunge-rate.alert-cooldown-ms`. `grimml.bypass.webrepeat` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Stun Web (90%) x1
Details: 90% of the last 10 stun webs identical to the tick: swap-in 0t, swap away 1t, cobweb place 1t
```

### Notes and history

- 1.1.0: first release, on by default.
