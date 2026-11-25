### How the cheat works

Aim Assist does not attack for you. While you aim at a player, it adds a small extra turn each tick that pulls your crosshair toward them. You still move the mouse, so it looks like normal aim, just more accurate. Settings usually control pull speed, strength, the field of view it acts in, and whether it aims at the centre or the nearest point of the hitbox.

### What GrimML measures

MLAimAssist is a LightGBM model. It scores the last 8 seconds of a fight every 2 seconds. Besides the 13 core numbers (rotation speed and jerk, snaps, pitch, movement, clicking; see [Kill Aura](Kill-Aura)), the v10 model reads:

- **Aim error:** how far the crosshair is from the opponent in yaw and pitch, on average and as a median.
- **Pull:** how often the turn reduces the error, and how closely turn speed follows the error.
- **Tracking lag:** how many ticks the view lags behind the opponent's movement.
- **Mouse grid:** a real mouse turns the view in whole steps of the player's sensitivity. An assist adds turns that fall between those steps. The model sees what share of the rotation is off the grid, and whether that off-grid part leans toward or away from the opponent.

The fight is judged like every ML check: every 4 minutes of fighting and when it ends, with an early alert when it is clearly over the line. **Alert line (v10): 33%** of the fight's windows.

This check runs next to the rule check [AimGrid](AimGrid). Both report as "Aim Assist".

### Why legit players do not flag

A hand's rotation sits on the sensitivity grid. Its small errors go in both directions, toward the target and away from it. An assist's extra rotation is off the grid and leans toward the target, in fight after fight. The alert line sits just above the highest share any legit 4-minute chunk reached in training.

### Known false flags and limits

- **Beta.** v10: held-out AUC 0.94, 10 of 11 cheat sessions caught. Its highest legit fight sits fairly close to the line, so expect the occasional legit alert.
- Mouse acceleration (Windows "Enhance pointer precision", Raw Accel, custom curves) and zoom or cinematic-camera mods move rotation off the grid. The model sees the toward/away lean, which helps, but this is the most likely source of false flags. UNVERIFIED how much on a live server.
- Silent Aim recordings from an older test setup also scored high on this model. Treat an alert as "aim is assisted", not proof of the exact module.
- Very low assist speed is the hardest to catch.
- Bedrock players are skipped.

### Tuning for your server

- The alert line comes with the model and cannot be changed in the config.
- `scan.early-alert-z`, `scan.fight-min-windows` and `scan.fight-block-windows` work as for every ML check (see [Kill Aura](Kill-Aura)).
- `punishments.groups.aim`: "Aim Assist" counts both MLAimAssist and AimGrid. To act on the ML check alone, list `MLAimAssist`.
- `grimml.bypass.mlaimassist`: never flag a player for this check. `grimml.bypass.aimgrid` covers the rule check.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Aim Assist (64%) x1
Details: 52 of 120 fight windows flagged (43%, alert line 33%)
```

### Notes and history

- v10 (2026-11-23): core, aim and grid features. Alert line 33%, caught 10/11, AUC 0.94.
- Next model (trained, not published yet): more Doomsday recordings at low speed. In testing: alert line about 43%, 11 of 12 caught. The line went up because a sweaty legit fight scored higher.
