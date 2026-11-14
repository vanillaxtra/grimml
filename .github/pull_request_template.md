## What

<!-- what this changes and why -->

## Part

- [ ] recorder
- [ ] detector
- [ ] training
- [ ] docs / ci

## Checklist

- [ ] `mvn clean package` passes (self-checks included)
- [ ] `pytest -q` in `training/` passes if format or features changed
- [ ] still Java 21 / Paper API 1.21.1, nothing heavy on the main thread
- [ ] no raw UUIDs, names, IPs, recordings, secrets or jars committed
- [ ] `config-version` bumped if config keys changed
- [ ] `docs/` updated if behaviour changed
