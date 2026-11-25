### How the cheat works

Kill Aura attacks players for you. It picks a target near you and turns your view toward it, or sends the rotation only to the server. It hits as soon as the target is in range and your attack is ready. Clients offer different styles. "Snap" turns straight onto the target on the hit tick. "Smooth" or "humanized" spreads the turn over a few ticks and adds random noise so it looks more like a hand. Most also hold a steady click rate, or hit exactly when the attack cooldown is full.

### What GrimML measures

MLKillAura is a LightGBM model. Every 2 seconds it scores the last 8 seconds of a player's fight, which is one "window". The current model (v10) reads 13 numbers from each window:

- **Rotation:** average and spread of the turn speed, how much the turn speed changes from tick to tick (jerk), how often the view snaps, and whether the rotation sits on the player's mouse sensitivity steps.
- **Pitch:** vertical turn speed and range.
- **Movement:** speed and the share of time on the ground.
- **Clicking:** clicks per second, the average gap between clicks and how even those gaps are.

A single window never alerts on its own. GrimML adds up a fight's windows and flags the fight only when enough of them score as cheating:

- The fight is judged every 4 minutes of fighting (120 windows) and once more when it ends (20 s without attacking).
- Stretches under about 40 s of fighting (20 windows) are never judged.
- **Alert line (v10): 28%** of the fight's windows.
- A fight that is clearly over the line alerts early, after about 20 s. "Clearly over" means the lower end of a 3-sigma confidence interval on the flagged share is above the line.

### Why legit players do not flag

The model only learned what differs between cheating fights and legit fights. Legit training data is cut into separate 4-minute chunks, and the alert line is set just above the highest share any legit chunk reached. Even a real player's best-aimed fight has some ordinary windows: missed turns, re-aiming, strafing. Kill Aura keeps the same rotation and click pattern for the whole fight, so its windows flag together. Judging the whole fight means a lucky stretch of a few windows is not enough.

### Known false flags and limits

- **Beta.** v10 was trained on a small number of players. Every cheat recording came from one tester. Held-out AUC is 0.80, and 7 of 8 cheat sessions were caught at the line.
- Kill Aura and Silent Aim overlap: each model also scores the other cheat's recordings high. Treat a Kill Aura alert as "aim is not human", not proof of that exact module.
- Very fast or very jittery legit aim (high sensitivity, shaky hands, some mice) moves the same numbers. UNVERIFIED how often this happens on a live server.
- Smooth or humanized settings at low speed are the hardest to tell apart from a hand and are the ones most likely to be missed.
- Bedrock players (Floodgate) are skipped (`scan.skip-bedrock`).

### Tuning for your server

You cannot move the alert line in the config: it comes with each model and changes when models update. You can change:

- `scan.early-alert-z`: lower (2.0) alerts sooner but adds false flags. `0` turns early alerts off, so fights are only judged per block.
- `scan.fight-min-windows` and `scan.fight-block-windows`: how much fighting is needed before a fight is judged.
- `punishments.groups.kill-aura`: commands at a number of violations. One violation is one flagged fight. Keep punishments off until the alerts look right on your server.
- `grimml.bypass.mlkillaura`: never flag a player for this check.

### Example alerts

The player name and numbers below are illustrative, not from a real alert.

```
GrimML » Player flagged Kill Aura (71%) x1
```

Hover / verbose:

```
Kill Aura (MLKillAura)
Details: 31 of 64 fight windows flagged (48%, alert line 28%)
```

The % in brackets is the model's average cheat score over the fight. The verbose line shows how many windows crossed the window threshold, against the alert line.

### Notes and history

- v10 (2026-11-23): 13 core features, alert line 28%, caught 7/8, AUC 0.80.
- Next model (trained, not published yet): adds aim, grid, trigger and click features and more clients (Grim, Doomsday, Vape, Prestige). In testing: alert line about 25%, 9 of 11 cheat sessions caught.
