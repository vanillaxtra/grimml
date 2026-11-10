# known issues

- [ ] `MsptGuard` clears any pause, including a disk-cap pause and `/grimml pause`, once MSPT drops below 80% of the threshold
- [ ] GML1/GML2 files are not decoded by the Java encoder (magic only); Python `grimml-train` decodes all three schemas (see `training/docs/format-notes.md`)
- [ ] `AimModulo360` / `Reach` in `taxonomy.yml` `expected_grim` and `grim-weights.yml` are guesses until a live FlagEvent confirms names
- [ ] grim-auto labels must stay out of eval; sample 100 `GRIM_WEAK` windows for staff review before trusting the thresholds
- [ ] grimml-synth lives in `Documents/grimml-synth`; calibrate PARAM_SPACE from real test-account sessions before trusting it
- [ ] Spear jab vs charge is recorded as a normal damage/attack pair; PacketEvents does not expose a dedicated spear packet (VERIFY)
- [ ] Own-inventory open has no client packet on some versions; we infer from the first click
- [ ] Grim prediction offset is not in GrimAPI 1.3.2.1 so setback records have position only
- [ ] Enchant levels on the inventory mirror are not filled from PacketEvents yet (category and name hash are)
- [ ] No Folia-aware schedulers yet; current code uses Bukkit scheduler and works on Paper
- [ ] Bedrock detection uses reflection; review after Via/Floodgate API changes
- [ ] `exclude_flagged_from_train` is still true; 40k slice: valid_partial 95%, valid_lag 92%, **valid_world 0%**, valid_gamemode 1.8% — leaving it on empties train. waiting on operator approval
- [ ] Uncapped ingest of remaining ~1.21M files (of 1,248,711 / 5.9 GB on E:/recordings) not started; dry-run ETA ~38h. resume via `E:/recordings_processed/ingest/done.txt`
- [ ] Phase 2 offline labeling pipeline is not built yet (roster.csv is the join key for SUSPECT bans)
- [ ] Ban-reason keywords are substring matches so staff should keep LiteBans reasons specific (`killaura`, not `cheating`)
- [ ] Grim 2.3.74 `PacketHidePlayerInfo` throws `IndexOutOfBoundsException` on 1.21.11 `PlayerInfoUpdate` (shaded PacketEvents vs Leaf/TAB/AntiPopup). that is Grim, not grimml
- [ ] Grim 2.3.74 warns that the string `FlagEvent` handler is deprecated; compile-time GrimAPI 1.3.2.1 only has that signature
