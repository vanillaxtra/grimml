### How the cheat works

Silent Aim sends the server a rotation onto the target when you hit, while your own screen stays where you were looking. The server thinks you aimed perfectly, and you never see your view move. "Continuous tracking" variants keep sending the target rotation while a target is near. Without it, the client only turns on the hit tick and turns straight back afterwards.

### What GrimML measures

MLSilentAim is a LightGBM model that scores the last 8 seconds of a fight every 2 seconds. The live model (v10) reads the 13 core numbers (rotation speed and jerk, snaps, pitch, movement, clicking; see [Kill Aura](Kill-Aura)). A silent rotation shows up as a sudden turn with no build-up, often followed by an equally sudden turn back.

The fight is judged every 4 minutes of fighting and when it ends, with an early alert when it is clearly over the line. **Alert line (v10): 27%** of the fight's windows.

### Why legit players do not flag

A real flick builds up and slows down over a few ticks. After a hit, the player keeps tracking the target or keeps turning the same way. They don't snap back to where they were before. Silent Aim's rotation jumps on the hit tick and often reverses straight after, on most hits in a fight. One lucky flick doesn't flag: the fight is judged on the share of its windows.

### Known false flags and limits

- **Beta.** v10: AUC 0.82, 8 of 9 cheat sessions caught. It **mostly misses Silent Aim with continuous tracking off** (those sessions scored 9–32%).
- Kill Aura and Silent Aim overlap, so each model scores the other cheat's fights high.
- Knockback-displacement flicks and fast 180° turns by real players move the same numbers. UNVERIFIED how often they flag live.
- Every Silent Aim recording so far comes from one client.
- Bedrock players are skipped.

### Tuning for your server

- The alert line comes with the model.
- The default `punishments.groups.silent-aim` group is disabled (`enabled: false`), decays faster (`decay-per-minute: 0.1`) and only kicks at 8 violations. Keep it that way while the check is in beta.
- `grimml.bypass.mlsilentaim`: never flag a player for this check.

### Example alerts

The player name and numbers below are illustrative.

```
GrimML » Player flagged Silent Aim (77%) x1
Details: 40 of 70 fight windows flagged (57%, alert line 27%)
```

### Notes and history

- v10 (2026-11-23): core features only. Alert line 27%, caught 8/9, AUC 0.82.
- 2026-11-25: added the **flick** features. For every hit they measure the turn over the 3 client ticks before and after it:
  - how often the hit tick alone carries over 80% of the turn,
  - how often over 60% of the turn goes back afterwards,
  - how often both happen.
  
  In recordings, legit fights did a one-tick flick on 0.6–7.5% of hits, Silent Aim on 12–31%.
- Next model (trained, not published yet): trains only on newer recordings that have client ticks, using the aim, grid and flick features. In testing: alert line about 23%, all 7 cheat sessions caught (86–100%), including continuous tracking off. Needs 1.21.2+ clients for the flick features.
