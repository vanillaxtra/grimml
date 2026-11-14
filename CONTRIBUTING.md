# Contributing

Thanks for helping. Read this before opening a pull request.

## Setup

Needs JDK 21+ and Maven. Python 3 for `training/`.

```bash
mvn clean package                 # builds recorder and detector, runs the self-checks
mvn clean package -DskipTests     # build without self-checks
cd training && pytest -q          # python tests
```

There is no test framework. Checks are assert-style `*SelfCheck` mains that run in the Maven test phase.
If you change `BinaryFormat`, `CombatSnapshot`, `RecordTypes` or the feature extraction, run both the Maven build and `pytest`. Java and Python features must stay in sync.

## Rules

- **Java 21 bytecode, Paper API 1.21.1.** The jars must load on 1.21.1 and 26.x. Don't raise `java.version` and don't use newer API.
- **Folia safe.** Schedule through `scheduler/Tasks`, never `Bukkit.getScheduler()`.
- **Hot path stays cheap.** Packet handlers must not allocate per packet, lock, look up entities or call Bukkit API.
- **Privacy.** Raw UUIDs, names and IPs never reach disk. Use `Anonymizer`.
- **Never punish.** The detector only alerts through Grim.
- **Layout.** One package per function, one `.java` file per feature. Command routers and the packet listener only dispatch.
- **Comments.** Single line, lowercase, only for what the code can't say.
- **Config.** Bump `config-version` in `config.yml` when keys change.

## Pull requests

1. Branch from `main`.
2. Keep one logical change per pull request.
3. Commit messages: short, lowercase, imperative, prefixed with the area, e.g. `writer: drop newest when queue full`.
4. Update `docs/` when you change behaviour (`decisions.md`, `known-issues.md`).
5. Make sure `mvn clean package` passes.

Don't commit recordings (`.gmlml`), world folders, logs, jars, secrets, or anything with real player data.

## License

By contributing you agree that your work is licensed under the [MIT License](LICENSE).
