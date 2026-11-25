# 🧠 All Checks

GrimML has two kinds of checks: **ML checks** score whole fights with a trained model, **rule checks** look for
something a hand or the vanilla client cannot do.

### ML checks (trained models)

| Check | Internal name | Catches |
|-------|---------------|---------|
| [Kill Aura](Kill-Aura) | `MLKillAura` | Automatic attacking and aiming at whoever is in reach. |
| [Aim Assist (ML)](Aim-Assist) | `MLAimAssist` | Rotation pulled toward the opponent, even when smoothed and randomized. |
| [Triggerbot](Triggerbot) | `MLTriggerbot` | Attacks fired the moment the crosshair is on a target. |
| [Silent Aim](Silent-Aim) | `MLSilentAim` | Server-side aim that differs from what the player sees. |

### Rule checks (no model, exact)

| Check | Internal name | Default | Catches |
|-------|---------------|---------|---------|
| [Aim Assist (AimGrid)](AimGrid) | `AimGrid` | On | Mouse rotation that is not a whole number of sensitivity steps and leans toward the opponent. |
| [Instant Lunge (LungeRate)](LungeRate) | `LungeRate` | On | Swap-lunges chained faster than a hand can. |
| [Instant Lunge (LungeHold)](LungeHold) | `LungeHold` | On | The spear held for exactly one client tick: swap in, jab, swap back on the next tick. |
| [Swap Macro (SwapOrder)](SwapOrder) | `SwapOrder` | On | A hotbar swap sent after an attack or spear jab in the same client tick. |
| [Shield Breaker (AxeHold)](AxeHold) | `AxeHold` | **Off** | Swapping away from the axe exactly one tick after breaking a shield, every time. |
| [StunRepeat](StunRepeat) | `StunRepeat` | **Off** | Axe stuns (swap in, axe hit, swap away, follow-up hit) repeated with exactly the same tick timing. |
| [SlamRepeat](SlamRepeat) | `SlamRepeat` | **Off** | Stun slams (axe hit, swap to mace, mace hit within 500 ms) repeated with the same tick timing. |
| [WebRepeat](WebRepeat) | `WebRepeat` | On | Stun webs (axe hit, swap to cobwebs, cobweb placed within 1 s) repeated with the same tick timing. |

### Short names

Short names group checks in alerts and [Punishments](Punishments).

| Short name | Checks |
|------------|--------|
| `Kill Aura` | MLKillAura |
| `Aim Assist` | MLAimAssist + AimGrid |
| `Triggerbot` | MLTriggerbot |
| `Silent Aim` | MLSilentAim |
| `Instant Lunge` | LungeRate + LungeHold |
| `Swap Macro` | SwapOrder |
| `Shield Breaker` | AxeHold |
| `*` | every check |

---

[🏠 Home](Home) · [🧠 All Checks](Checks) · [⚙️ Configuration](Configuration)
