# decisions

## 2026-10-28 - Phase 1 recorder only

- no detection, no punishments, no ML inference; only capture combat windows to disk
- keep PacketEvents path allocation-free and lock-free; metadata sampling lives on the main thread
- custom compressed binary `.gmlml` instead of Parquet; smaller footprint, no extra jar dependencies
- combat-only recording: rolling 5s pre-buffer + 2s post-buffer per player, flushed only around attacks
- hit offset not computed in phase 1; requires entity lookup so it is deferred to offline feature extraction
- anonymize player ids with salted SHA-256; session ids are random per login
- Java 21 bytecode targets both 1.21.1 and 26.x servers; PacketEvents covers protocol differences
- GrimAPI `1.3.2.1` is provided and used only as a soft-depend; plugin still loads without Grim
- writer uses a bounded queue and drops newest snapshots if it falls behind; never stalls the packet path
- auto-pause on high MSPT and disk cap are advisory; manual `/grimml pause` also available
- comments are single-line, lowercase, no punctuation, only what the code cannot say
