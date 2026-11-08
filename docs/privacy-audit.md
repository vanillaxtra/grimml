# privacy audit

Last run 2026-11-08 against `rev`. Repeat when a capture path, sidecar or command output changes.

| data | where | identity | status |
|---|---|---|---|
| recordings `.gmlml` | `recordings/<day>/<hour>/` | salted anon id + random session id, target anon ids | ok |
| `sessions.idx`, `grim-labels.csv`, `grim-review.csv` | plugin folder | anon ids only | ok |
| model alerts | staff chat (`grimml.alerts`), console | chat shows the player name; console and `grim-review.csv` keep anon ids | ok, names never on disk |
| `marks.csv` | plugin folder | account + operator names | staff ledger, never training input; keep out of shared dumps |
| `test-accounts.txt` | plugin folder | raw UUIDs | operator config, never shipped with recordings |
| map snapshots `maps/*.gmlmap` | plugin folder | block data + absolute coordinates | skipped in `position-mode: relative` so survival bases do not leak |
| chat, IP addresses | - | - | not captured anywhere (grep `getAddress`, chat events: none) |

Survival servers: use `position-mode: relative`. Frames are then origin-shifted per window, and both the target geometry features and map snapshots are skipped.
