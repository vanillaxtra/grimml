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
