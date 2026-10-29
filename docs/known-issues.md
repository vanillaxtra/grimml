# known issues

- [ ] `MsptGuard` clears any pause, including a disk-cap pause and `/grimml pause`, once MSPT drops below 80% of the threshold
- [ ] GML1/GML2 files are not decoded by the current Java encoder; they are only identifiable by magic (`schemaOf`)
- [ ] `AimModulo360` / `Reach` in `taxonomy.yml` `expected_grim` are guesses until a live FlagEvent confirms names
- [ ] grimml-synth (offline synthetic windows) is a sibling repo, not this plugin
- [ ] Spear jab vs charge is recorded as a normal damage/attack pair; PacketEvents does not expose a dedicated spear packet (VERIFY)
- [ ] Own-inventory open has no client packet on some versions; we infer from the first click
- [ ] Grim prediction offset is not in GrimAPI 1.3.2.1 so setback records have position only
- [ ] Enchant levels on the inventory mirror are not filled from PacketEvents yet (category and name hash are)
- [ ] No Folia-aware schedulers yet; current code uses Bukkit scheduler and works on Paper
- [ ] Bedrock detection uses reflection; review after Via/Floodgate API changes
- [ ] Phase 2 offline labeling pipeline is not built yet (roster.csv is the join key for SUSPECT bans)
- [ ] Ban-reason keywords are substring matches so staff should keep LiteBans reasons specific (`killaura`, not `cheating`)
