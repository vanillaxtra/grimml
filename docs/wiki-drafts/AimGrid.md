### How the cheat works

See [Aim Assist (ML)](Aim-Assist). Aim Assist adds a small turn toward the target each tick on top of your own mouse movement.

### What GrimML measures

A mouse turns the view in whole steps: the client multiplies mouse counts by a factor from your sensitivity setting, so every real turn is a whole number of those steps. Aim Assist adds rotation that is computed, not counted, so it falls between the steps ("off the grid").

AimGrid works in three stages:

1. **Learns each player's step** from their own rotation, also outside fights. Players whose rotation fits no mouse grid at all (controllers, cinematic camera) are skipped.
2. **Learns their baseline:** the off-grid share of their rotation *outside* fights, where an assist has nothing to pull toward. Fights are only tested once it has `baseline-samples` (100) samples, about 5 s of moving the mouse outside a fight. Players over `baseline-max` (8%) off-grid outside fights are skipped.
3. **Scores fights:** during a fight, a running score adds up off-grid rotation against the expected rate. The expected rate is the larger of `legit-off-rate` and their baseline × `baseline-margin`. It alerts when the score reaches `alert-score` (20) **and** the off-grid part leans toward the opponent by at least `min-toward-bias`.

Moves larger than `max-steps` (20 steps per tick) are ignored. Acceleration curves bend fast flicks the most, and assist corrections are small anyway.

### Why legit players do not flag

A legit mouse is almost never off the grid (measured 0.0–0.2%). When it is, because of zoom, cinematic camera or an unusual mouse, it is off in every direction, not toward the opponent. Players with non-linear mice are compared to their own rate outside fights, not a fixed number. In recorded fights, legit players never got above a score of 2.3. The alert is at 20, and aim assist reached 20 within 1.5–6 s once the step was known.

### Known false flags and limits

- After the baseline was added, 2 of 30 recorded legit fights still alerted: one spear fight and one stretch with the cinematic camera.
- A player who goes straight into a fight with no mouse movement first has no baseline, so they are not tested until they have one. Bot-fight recordings showed this: 2 of 4 aim assist sessions never built one. Real players usually move around outside fights first. UNVERIFIED live.
- A cheat that rounds its turn to the player's grid would avoid this check. The ML check still looks at other signs.
- Controllers, cinematic camera, and players over 8% off-grid outside fights are skipped.

### Tuning for your server

```yaml
aim-grid:
  enabled: true
  alert-score: 20.0        # higher = slower, fewer false flags
  min-toward-bias: 0.05    # how much the off-grid part must lean toward the opponent
  baseline-margin: 1.5     # how far above their own rate a fight must be
  baseline-max: 0.08       # skip players more off-grid than this outside fights
  baseline-samples: 100
  max-steps: 20
  alert-cooldown-ms: 15000
```

If players with unusual mice flag, raise `baseline-margin` first. Use `/grimml status <player>` to see the learned step, score, baseline and legit rate. `grimml.bypass.aimgrid` exempts a player.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Aim Assist (81%) x1
Details: 22% of recent rotation off the mouse grid (their own rate outside fights 0.4%), 81% of that toward the opponent
```

For AimGrid the % in brackets is the share of off-grid rotation that went toward the opponent.

### Notes and history

- 1.1.0: first release.
- Unreleased: per-player baseline, `baseline-max` skip and `max-steps` cap, so non-linear mice no longer flag. Legit fights alerting on replay went from 7/30 to 2/30.
