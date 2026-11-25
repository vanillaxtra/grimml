# 🧾 Commands and Permissions

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/grimml alerts` | `grimml.alerts` | Turn your alerts on or off |
| `/grimml status [player]` | `grimml.admin` | Models, checks, punishments, and a player's scan state and counts |
| `/grimml update` | `grimml.admin` | Download newer models now |
| `/grimml reload` | `grimml.admin` | Reload `config.yml` |

**Alias:** `/gml`

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `grimml.*` | OP | Everything below except bypass |
| `grimml.alerts` | OP | See alerts in chat |
| `grimml.alerts.sound` | OP | Hear a ping with every alert |
| `grimml.notify` | OP | Be told once when new models are downloaded |
| `grimml.update.notify` | OP | Be told when a new GrimML release is out |
| `grimml.admin` | OP | Status, reload, update |
| `grimml.bypass` | false | Never scanned by any check |
| `grimml.bypass.<check>` | false | Never flagged by one check (see each [check page](Checks)) |

## Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%player%` / `%uuid%` | Player name / UUID |
| `%check%` | Short name, e.g. `Kill Aura` |
| `%check_name%` | Internal name, e.g. `MLKillAura` |
| `%description%` | What the check looks for |
| `%confidence%` | 0–100 |
| `%vl%` / `%violations%` | Violations of this check |
| `%verbose%` | What was measured |
| `%ping%` / `%version%` / `%brand%` | Ping, client version, client brand |
| `%tps%` / `%grimml_version%` | Server TPS, GrimML version |
| `%group%` / `%group_vl%` | Punishments only: group name and its violations |
| `%commands%` | Punishment webhook only: the commands that ran |

---

[🏠 Home](Home) · [🧠 All Checks](Checks) · [⚙️ Configuration](Configuration)
