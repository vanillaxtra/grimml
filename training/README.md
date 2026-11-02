# grimml training pipeline

Decode a mixed folder of `.gmlml` combat windows (cheat and legit together), recover labels from **MARKER records inside the files**, train a baseline.

## 80GB unsorted dump

Files are not sorted by class. `/grimml mark` writes CHEAT / LEGIT_CONFIRMED as time-range MARKER events in the recording. Ingest walks one directory tree, processes files in parallel, and never loads the whole dump into RAM. Frames are discarded after per-window features.

```bash
cd training
pip install -e ".[dev]"
```

Edit `config.example.yaml`:

```yaml
recordings_dir: D:/grimml-dump   # the 80GB folder
out_dir: out
workers: 0                       # 0 = all CPUs
shard_size: 4000
keep_unlabeled: true
skip_bad_files: true
resume: true
```

```bash
grimml-train --config config.example.yaml pipeline
```

Or step by step: `ingest` → `label` → `inventory` → `train` → `eval`.

Resume: rerun `ingest` after a crash; `out/ingest/done.txt` skips files already written. Corrupt files go to `out/ingest/errors.log`.

UNLABELED windows (no mark segment) are never treated as legit. Training uses only `CHEAT` vs `LEGIT_CONFIRMED` from staff markers.

See [TODOtraining.md](TODOtraining.md).
