# grimml training pipeline

Decode `.gmlml` combat windows (GML1 / GML2 / GML3), recover labels from in-file MARKER records (GML2/3) plus optional sidecars, build features, train a leakage-safe baseline.

Format details: [docs/format-notes.md](docs/format-notes.md). Leakage audit: [AUDIT.md](AUDIT.md).

## Run order

`decode` → `inventory` → `label` → `features` (computed during decode) → `train` → `eval` → `export`

```bash
cd training
pip install -e ".[dev]"
```

`config.yaml` already points at `E:/recordings`.

```bash
grimml-train --config config.yaml decode
grimml-train --config config.yaml inventory
grimml-train --config config.yaml label
grimml-train --config config.yaml train
grimml-train --config config.yaml eval
grimml-train --config config.yaml export
```

Or `grimml-train --config config.yaml pipeline`.

Resume: `out/ingest/done.txt` skips finished files. Failures go to `out/ingest/errors.csv` (`path,reason`). One bad file does not stop the batch.

UNLABELED is never treated as legit. Train uses staff `CHEAT` vs `LEGIT_CONFIRMED` only.

### Current dump (`E:\recordings`)

Early files are **GML1** (yaw/pitch only, no MARKER, no xyz). Inventory will warn if GML1 dominates. Staff marks for those windows need `cheat_log.csv` / `marks.csv` / other sidecars in `sidecar_dir`. GML3 marked sessions (`*_m########_*.gmlml`) join by marker time range plus `mark_id`.
