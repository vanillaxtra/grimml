# grimml - architecture

- **target:** Paper 1.21.1 through 26.x, Java 21 bytecode, Maven
- **packet capture:** PacketEvents 2.14.0
- **schema:** GML2 (schema 2); old GML1 files stay readable by magic
- **depends:** packetevents (hard, used directly at enable)
- **soft-depends:** GrimAC, Geyser-Spigot, floodgate
- **commands:** `/grimml` registered via Paper lifecycle `LifecycleEvents.COMMANDS` as a `BasicCommand`; plugin.yml has no `commands:` section
- **config schema version:** 2

## structure
one folder per function, one file per feature

- `clock/TickClock.java` - main-thread tick, duration, boot id, epoch pairing
- `buffer/FrameRing.java` - seqlock per-tick frames
- `buffer/EventRing.java` - seqlock events
- `buffer/InventoryMirror.java` - player inv 0-45 from set-slot packets
- `buffer/EntityIndex.java` - entityId to anonId
- `buffer/PlayerRingBuffer.java` - live state plus rings
- `buffer/SessionRegistry.java` - uuid map
- `buffer/CombatSnapshot.java` - pooled copy for the writer
- `fight/FightAssembler.java` - writer-thread attacker/target/bystander snapshots
- `capture/` - PacketEvents handlers (flying, input, attack, held, inventory, actions, outgoing)
- `listeners/DamageListener.java`, `listeners/ValidityListener.java`
- `item/ItemCategories.java`, `item/ItemUtil.java`
- `format/BinaryFormat.java` - GML2 encoder
- `writer/RecordWriter.java` - async drain, session sidecar
- `commands/` - status, pause, resume, reload, mark, testaccount

## data flow

```mermaid
flowchart LR
    A[PacketEvents netty] -->|primitives seqlock| B[PlayerRingBuffer]
    C[Main tick sampler] -->|staggered bukkit state| B
    D[Damage validity grim] -->|event primitives| B
    E[Writer thread] -->|seqlock copy nearby| F[FightAssembler]
    F --> G[.gmlml GML2]
    H[grimml-disk] -->|pause if over cap| E
```

## commands & permissions

| command | permission | notes |
|---|---|---|
| `/grimml status` | `grimml.admin` | windows, drops, bytes |
| `/grimml pause` | `grimml.admin` | stop flushing |
| `/grimml resume` | `grimml.admin` | resume flushing |
| `/grimml reload` | `grimml.admin` | reload config |
| `/grimml mark <start\|end> <label> [client] [module] [settings]` | `grimml.admin` | MARKER record plus session sidecar |
| `/grimml testaccount [on\|off]` | `grimml.admin` | flags this session as test traffic |
