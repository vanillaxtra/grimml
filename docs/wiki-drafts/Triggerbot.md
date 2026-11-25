### How the cheat works

Triggerbot clicks for you. It does not move your view: you aim yourself, and the moment your crosshair is on a player (and, usually, your attack cooldown is full) it attacks. Settings add a random delay before the click, a cooldown share to wait for, and sometimes deliberate misses so the timing looks human.

### What GrimML measures

MLTriggerbot is a LightGBM model that scores the last 8 seconds of a fight every 2 seconds. Besides the 13 core numbers (see [Kill Aura](Kill-Aura)), the v10 model reads:

- **Trigger timing:** the share of attacks made while the crosshair is on the opponent, and how many ticks pass between the crosshair entering the hitbox and the click (average and spread).
- **Cooldown:** how full the attack cooldown is at each hit (average and spread), and how often clicks land while the opponent is still in hurt time.
- **Click rhythm:** how the gaps between clicks are spread out (kurtosis, entropy), and how often clicks come in doubles.

The fight is judged every 4 minutes of fighting and when it ends, with an early alert when it is clearly over the line. **Alert line (v10): 26%** of the fight's windows.

### Why legit players do not flag

Players do react to the crosshair landing on someone, but human reaction time varies a lot from hit to hit. Humans also click while the crosshair is still moving onto the target, and sometimes click early or late. A triggerbot fires at the same short delay after entry, almost only when on target, at a steady cooldown, fight after fight. Even with random delay added, its delays spread out in a much narrower and flatter way than a hand's.

### Known false flags and limits

- **Beta.** v10 was trained on few sessions: 4 of 4 caught, AUC 0.93. The training report still shows a leak warning: some of the separation may come from how the sessions were recorded, not from the cheat. More recordings are being made to fix this.
- Players who click only when on target and wait for full cooldown (strong 1.9+ players) look closest to it. UNVERIFIED how often they flag live.
- Long random delays and miss chance make the cheat harder to catch.
- Bedrock players are skipped.

### Tuning for your server

- The alert line comes with the model.
- `scan.early-alert-z`, `scan.fight-min-windows` and `scan.fight-block-windows` work as for every ML check.
- `punishments.groups.aim` includes Triggerbot by default. Give it its own group to act on it separately.
- `grimml.bypass.mltriggerbot`: never flag a player for this check.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Triggerbot (58%) x1
Details: 19 of 48 fight windows flagged (40%, alert line 26%)
```

### Notes and history

- v10 (2026-11-23): core, trigger and click features. Alert line 26%, caught 4/4, AUC 0.93.
- Next model (trained, not published yet): more clients and delay settings. In testing: alert line about 39%, 6 of 9 caught. The leak warning is still open.
