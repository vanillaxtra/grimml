### How the cheat works

Swap macros (Instant Lunge, auto mace, auto shield breaker, "attribute swap" and similar) swap your hotbar slot, attack, and swap back. To be fast, many do all of it within a single client tick, in whatever order is quickest for them.

### What GrimML measures

The order of packets inside one client tick. The vanilla client processes the hotbar key **before** attacks within a tick, so its slot change always comes before an attack in that tick. A hotbar swap sent *after* an attack or spear jab in the same client tick is something the vanilla client can't do. A client that swaps, hits and swaps back within one tick does it every time.

The check alerts when this happens `count` (2) times within `window-ms` (60 s).

### Why legit players do not flag

It never happens by hand: 0 times in about 1,400 recorded legit hits and swaps. However fast someone presses keys, the vanilla client still sends the swap before the attack.

### Known false flags and limits

- **Needs 1.21.2+ clients** (client ticks).
- Modded clients that change input handling could, in theory, send this order without cheating. UNVERIFIED: no such client has been seen. If you allow modded clients, watch the first alerts.
- A proxy or protocol translator that reorders packets would also break this. UNVERIFIED: not seen with ViaVersion in testing.
- A macro that spreads its steps over separate ticks avoids this check. LungeRate and LungeHold still apply.

### Tuning for your server

```yaml
swap-order:
  enabled: true
  count: 2          # same-tick swaps after an attack ...
  window-ms: 60000  # ... within this long
```

It is "Swap Macro" in `punishments`, grouped with Instant Lunge by default. `grimml.bypass.swaporder` exempts a player.

### Example alerts

The player name below is illustrative.

```
GrimML » Player flagged Swap Macro (100%) x1
Details: 2 hotbar swaps sent after an attack in the same tick within 60 s
```

Confidence is always 100% for this check, because vanilla never sends this order.

### Notes and history

- 1.1.0: first release.
