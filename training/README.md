# grimml training pipeline

Decode `.gmlml` combat windows (GML1 / GML2 / GML3), recover labels from in-file MARKER records (GML2/3) plus optional sidecars, build features, train a leakage-safe baseline.

Format details: [docs/format-notes.md](docs/format-notes.md). Leakage audit: [AUDIT.md](AUDIT.md).

## Run order

`decode` → `inventory` → `label` → `features` (computed during decode) → `train` → `eval` → `export`

```bash
cd training
pip install -e ".[dev]"
```

`config.yaml` points at `E:/recordings` and writes processed parquet to `E:/recordings_processed`.

```bash
grimml-train --config config.yaml ingest --dry-run
grimml-train --config config.yaml ingest --max-files 20000
grimml-train --config config.yaml ingest
grimml-train --config config.yaml label --bulk
grimml-train --config config.yaml inventory
grimml-train --config config.yaml sample
```

Or `grimml-train --config config.yaml pipeline`.

Resume: `E:/recordings_processed/ingest/done.txt` skips finished files. Failures go to `errors.csv` (`path,category,reason`). One bad file does not stop the batch. Ctrl+C is safe; rerun `ingest` to continue.

UNLABELED is never treated as legit. Train uses staff `CHEAT` vs `LEGIT_CONFIRMED` only.

### Current dump (`E:\recordings`)

Early files are **GML1** (yaw/pitch only, no MARKER, no xyz). Inventory will warn if GML1 dominates. Staff marks for those windows need `cheat_log.csv` / `marks.csv` / other sidecars in `sidecar_dir`. GML3 marked sessions (`*_m########_*.gmlml`) join by marker time range plus `mark_id`.

### Sticky validity bits (old recorder builds)

Recorder builds before the 2026-11-10 fix never cleared the `valid_death/respawn/teleport/world/gamemode/vanish/lag` bits, so after a player's first respawn, gamemode change or teleport **every later window carried the bit**. In those files the event bits are not usable (Prestige legit recording: `valid_gamemode` on 513/513 windows; the hard-validity filter dropped all of it). The fixed plugin sets a bit only when the event happened inside that window.

Files record when they ran: the window header has `epoch_ms` (UTC wall clock) and the file name starts with the date. `prestige_quick.py` uses it automatically:

- set `VALIDITY_FIXED_FROM_MS` in `prestige_quick.py` (or pass `--validity-fixed-from 2026-11-12T18:00:00Z`) to the time the fixed jar went live; the default `None` means no fixed build yet, so every file is treated as old
- windows at/after that time get the hard-validity filter; earlier windows ignore it (this replaces typing `--keep-hard-invalid` by hand)
- overrides: `--keep-hard-invalid` never filters, `--drop-hard-invalid` always filters
- the report notes how many windows were filtered vs ignored

The main pipeline (`ingest`, `exclude_flagged_from_train`) does **not** have this cutoff yet; changing train filters needs operator approval. See `docs/known-issues.md`.

### Trial model on Prestige + bulk legit

`python prestige_quick.py --bulk-processed E:/recordings_processed/ingest/windows` adds legit windows from the earlier ingest (no scan, no decoding, ~15 s). The live hold-out accounts are excluded, only GML3 windows with role 1/2 are used, capped at 30 windows per player, and players are held out in groups. `--bulk-dir E:/recordings --bulk-files N` instead samples N fresh files; it first lists the whole folder once (very slow on millions of files, progress is printed, cached in `out_quick/bulk_listing.txt`). Bulk legit is operator-asserted, and comes from other servers than the cheat test server, so the shortcut-only line in the report decides whether a score is trustworthy.
