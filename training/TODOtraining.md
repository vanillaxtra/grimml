# grimml training pipeline — status

Python package under `training/`. Mixed unsorted `.gmlml` dumps are expected: cheat vs legit is inside MARKER records, not folders.

Leakage/eval audit: [AUDIT.md](AUDIT.md) (2026-11-02). All checklist FAILs have tests.

Legend: ✅ done · 🔶 partial · ⬜ todo

## ingest (E:/recordings → E:/recordings_processed)

- [x] Recurse one folder of unsorted `.gmlml` files
- [x] ProcessPool workers, bounded inflight queue (does not load 80GB)
- [x] Per-file decode + features; **frames not kept** (`keep_frames: false`)
- [x] Parquet shards (`shard_size`)
- [x] Resume via `done.txt` under `out_dir` (`E:/recordings_processed`)
- [x] Bad files logged to `errors.csv` (`skip_bad_files: true`)
- [x] `ingest --dry-run`, `--max-files`, `--shard-range`, skip files newer than `skip_recent_minutes`
- [x] Bulk operator-asserted LEGIT_CONFIRMED (`label --bulk` / `--undo`), below grim-weak, never on cheat_dir
- [x] `sample` live legit manifests + 20% account hold-out file
- [x] Labels recovered from in-file MARKER start/end time ranges (cheat + legit mixed)
- [x] UNLABELED never treated as legit
- [ ] 🔶 Optional persist frames for debugging (`keep_frames`)
- [ ] 🔶 Java BinaryFormatSelfCheck byte compare in CI

## decode/

- [x] GML1 / GML2 / GML3 by magic
- [x] Unknown record type raises per file (ingest continues if skip_bad_files)
- [x] Pytest fixtures for all three schemas

## labels/

- [x] Reconstruct mark segments from MARKER events across files in the same session
- [x] Trim seconds on segment ends
- [x] Priority: replay-confirmed > staff-marker > ban-backfill > grim-weak > operator_asserted_bulk > unlabeled
- [x] Sidecars optional (dump can be labels-only-in-file)
- [ ] 🔶 Decode MARKER_KV hashes to `client-name=` strings via taxonomy

## inventory / features / train / eval / export

- [x] Inventory: labeled vs unlabeled, cheat vs legit, schema/server/module ids
- [x] Rotation / GCD / snap / cps / ping features (GML1 xyz = NaN)
- [x] Grim flag counts not used as training features
- [x] LightGBM on staff CHEAT vs LEGIT_CONFIRMED only
- [x] Grouped split by anon id
- [x] ONNX best-effort export
- [ ] ⬜ Aim-error / reach vs target
- [ ] ⬜ Dual aim vs macro models, grouped CV, Bedrock/ping breakdowns

## CLI

```
grimml-train ingest     # scan dump → shards + features + markers
grimml-train label      # join MARKER time ranges (+ optional sidecars)
grimml-train inventory
grimml-train train
grimml-train eval
grimml-train export
grimml-train pipeline   # ingest → label → inventory → train → eval
```

Point `recordings_dir` at the 80GB folder. Files do not need sorting or renaming.
