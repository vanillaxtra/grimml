# known issues

- [ ] `MsptGuard` clears any pause, including a disk-cap pause and `/grimml pause`, once MSPT drops below 80% of the threshold
- [ ] Phase 1 does not compute hit offset from target hitbox; plan to add during offline feature extraction
- [ ] No Folia-aware schedulers yet; current code uses Bukkit scheduler and works on Paper
- [ ] Bedrock detection uses reflection; review after Via/Floodbase API changes
- [ ] Phase 2 labeling tools are not built yet
