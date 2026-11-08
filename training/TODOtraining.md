# grimml training pipeline — status

Python package under `training/`. Mixed unsorted `.gmlml` dumps are expected: cheat vs legit is inside MARKER records, not folders.

Leakage/eval audit: [AUDIT.md](AUDIT.md) (2026-11-02). All checklist FAILs have tests.

Legend: ✅ done · 🔶 partial · ⬜ todo

## ingest (80GB mixed dump)

- [x] Recurse one folder of unsorted `.gmlml` files
- [x] ProcessPool workers, bounded inflight queue (does not load 80GB)
- [x] Per-file decode + features; **frames not kept** (`keep_frames: false`)
- [x] Parquet shards (`shard_size`)
- [x] Resume via `out/ingest/done.txt`
- [x] Bad files logged to `errors.csv` (`skip_bad_files: true`)
- [x] Labels recovered from in-file MARKER start/end time ranges (cheat + legit mixed)
- [x] UNLABELED never treated as legit
- [x] Optional persist frames for debugging (`keep_frames: true` writes frames/events shards)
- [x] Java self-check writes `target/selfcheck/all-types.gmlml`; `test_java_parity` decodes it field by field and compares features (CI fails if it is missing)

## decode/

- [x] GML1 / GML2 / GML3 by magic
- [x] Unknown record type raises per file (ingest continues if skip_bad_files)
- [x] Pytest fixtures for all three schemas

## labels/

- [x] Reconstruct mark segments from MARKER events across files in the same session
- [x] Trim seconds on segment ends + `marker_min_overlap` (share of the window inside the segment)
- [x] Priority: replay-confirmed > staff-marker > ban-backfill > grim-weak > unlabeled
- [x] Sidecars optional (dump can be labels-only-in-file)
- [x] Decode MARKER_KV hashes via taxonomy + marks.csv + mark ids → `mark_client_name`, `mark_kv`

## inventory / features / train / eval / export

- [x] Inventory: labeled vs unlabeled, cheat vs legit, schema/server/module ids
- [x] Rotation / GCD / snap / cps / ping features (GML1 xyz = NaN)
- [x] Grim flag counts not used as training features
- [x] LightGBM on staff CHEAT vs LEGIT_CONFIRMED only
- [x] Grouped split by anon id
- [x] ONNX export with parity on every row (NaN kept); alert models limited to plugin-computable FEATURE_COLUMNS
- [x] Drift: `grimml-train drift` PSI vs `drift_reference`
- [x] Aim-error / reach / ray miss / LOS vs target (`grimml-train targets`, offline only, never exported)
- [x] Dual aim vs macro models, multitask family model, grouped CV, Bedrock/ping/client/module breakdowns (in `eval.json`)

## CLI

```
grimml-train ingest     # scan dump → shards + features + markers + tracks/attacks
grimml-train targets    # aim/reach/LOS per attacker window (maps from sidecar_dir/maps)
grimml-train label      # join MARKER time ranges (+ optional sidecars)
grimml-train inventory
grimml-train train
grimml-train eval
grimml-train export
grimml-train drift      # PSI per feature vs drift_reference
grimml-train pipeline   # ingest → targets → label → inventory → train → eval → export
```

Point `recordings_dir` at the 80GB folder. Files do not need sorting or renaming.
