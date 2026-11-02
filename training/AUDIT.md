# Training audit (leakage / optimistic bias)

Inspected `training/` against the anticheat evaluation checklist. **FAIL items were fixed with tests.** Model architecture (LightGBM binary) was not changed.

Legend: **PASS** = already correct · **FAIL → fixed** = was wrong; now guarded by a test.

| ID | Result | Where | Test |
|---|---|---|---|
| **A1** | PASS | `decode/window.py` `schema_version`; GML1 missing fields `None` (`boot_id`, `x_milli`, `valid_*`) | `test_gml1_missing_fields_are_null` |
| **A2** | PASS | Fixture `encode_gml3_selfcheck` uses the same constants as `BinaryFormatSelfCheck.java` (fight 11, window 22, taxonomy 1 / 0xabc, frames 1000→1010) | `test_gml2_taxonomy_null_gml3_present`, `test_decode_gml3_roundtrip` |
| **A3** | FAIL → fixed | All `VALID_*` bits decoded; `exclude_flagged_from_train` in config/cli; unknown types raise | `test_validity_flags_kept`, `test_unknown_record_type_raises` |
| **B1** | FAIL → fixed | `is_negative("UNLABELED")` is False; train `_xy` only `CHEAT` vs `LEGIT_CONFIRMED` | `test_unlabeled_is_not_negative` |
| **B2** | FAIL → fixed | Labels from **trimmed** marker segments only (removed untrimmed local-marker bypass); interrupted segments dropped when `exclude_interrupted` | `test_trim_drops_segment_edges`, `test_interrupted_excluded` |
| **B3** | FAIL → fixed | Priority table in `labels/join.py`; test split rejects grim-weak / ban-backfill / UNLABELED | `test_join_priority_replay_over_weak`, `test_weak_and_unlabeled_never_in_test` |
| **B4** | FAIL → fixed | `assert_no_forbidden_features` rejects `grim_*` / check hashes; called from train/export | `test_forbidden_identity_columns` |
| **C1** | FAIL → fixed | Grouped split by `anon_player_id`; `assert_no_leakage` on anon/session/fight/window; `require_operator_for_cheat` | `test_session_cannot_span_split`, `test_operator_required_for_cheat` |
| **C2** | FAIL → fixed | `leave_one_out` for client and operator; cli eval reports both | `test_leave_one_client_and_operator` |
| **C3** | FAIL → fixed | `confound_probe` AUC on LEGIT-only vs test-account/server/map/kit; warn if AUC > 0.6 | `test_confound_probe_warns_when_separable` |
| **C4** | FAIL → fixed | Identity/context names forbidden as inputs | `test_forbidden_identity_columns` |
| **C5** | FAIL → fixed | `CONFOUND_WATCH` (ping, jitter, frame_count, …); `warn_confound_importance` if in top 10 | covered via `evaluate()` → `confound_top10` |
| **D1** | FAIL → fixed | Features skip marker events; no label fields | `test_features_ignore_markers` |
| **D2** | FAIL → fixed | Scaler fit on train only; GML1 xyz stays NaN (not 0) | `test_scaler_fit_on_train_only`, `test_gml1_nan_not_zero` |
| **D3** | FAIL → fixed | `timing_ack_compensated` flag when ack ticks present | `test_features_ignore_markers` |
| **E1** | PASS | `class_weight="balanced"`; no resampling | `train/lgbm.py` `LGBMClassifier` |
| **E2** | FAIL → fixed | Threshold from validation ROC (max FPR), applied once on test | `test_threshold_then_single_test_apply` |
| **E3** | FAIL → fixed | `fp_per_1000_player_hours` is the first eval number | `test_fp_per_1000_hours_primary` |
| **E4** | FAIL → fixed | Bootstrap by **player** in `eval/report.py`; cli writes `eval.json` | `bootstrap_by_player` |
| **E5** | FAIL → fixed | `player_aggregate` decay + N-alert | `test_player_aggregation` |
| **E6** | FAIL → fixed | `assert_not_synthetic` on every test split | `test_synthetic_never_in_test` |
| **F1** | FAIL → fixed | `run_meta.json`: seed, config yaml dump, data manifest hash, git commit | `test_run_meta_written` |
| **F2** | FAIL → fixed | `model_meta.json` feature order + scaler + threshold; ONNX parity when converter works | `test_export_json_has_order_scaler_threshold` |

## Remaining caveats (not architecture)

- A2 compares a Python fixture that **mirrors** `BinaryFormatSelfCheck` field values; it does not byte-diff a `.gmlml` emitted by `mvn exec:java` on the plugin. Do that once per schema bump.
- Aim-error / reach vs target still not implemented (would be new features, deferred).
- `require_operator_for_cheat` will **refuse to train** until `roster.csv` has an `operator` column for cheat test accounts (or you add it as a marker setting). That is intentional.

## Data you still need (from inventory shape)

Collect until inventory shows all of:

| Slice | Why |
|---|---|
| Cheat **and** legit marks from the **same operators / same kit / same map** | C3: if confound AUC > 0.6, primary model must use same-session legit |
| ≥2 cheat **clients** with named `client-name=` | leave-one-client-out |
| ≥2 **operators** in roster | leave-one-operator-out; fail-loud if missing on cheat |
| Blatant / moderate / subtle as a marker setting (`tier=`) | recall-by-tier (column not populated yet) |
| Bedrock vs Java legit hours | ping/Bedrock breakdowns |
| Enough **confirmed-legit player-hours** on live (not only test accounts) | FP / 1,000 player-hours is the headline metric |

Until those exist, `eval.json` will show empty `leave_one_*` lists and/or `require_operator_for_cheat` errors — that is the pipeline working, not a green model.
