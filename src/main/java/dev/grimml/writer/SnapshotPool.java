package dev.grimml.writer;

import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.config.PluginConfig;

// reusable drain buffers so steady-state flushing does not allocate
public final class SnapshotPool {

    private final PluginConfig config;
    private final CombatSnapshot[] scratchPool;
    private int scratchIndex;

    public SnapshotPool(PluginConfig config) {
        this.config = config;
        int snapshotCapacity = Math.max(4, Integer.highestOneBit(config.writerQueueCapacity()) / 2);
        this.scratchPool = new CombatSnapshot[snapshotCapacity];
        for (int i = 0; i < scratchPool.length; i++) {
            scratchPool[i] = new CombatSnapshot(config.ringCapacity(), config.eventRingCapacity());
        }
    }

    public CombatSnapshot borrow() {
        CombatSnapshot[] pool = scratchPool;
        synchronized (pool) {
            if (scratchIndex < pool.length) {
                return pool[scratchIndex++];
            }
        }
        return new CombatSnapshot(config.ringCapacity(), config.eventRingCapacity());
    }

    public void giveBack(CombatSnapshot snap) {
        snap.reset();
        CombatSnapshot[] pool = scratchPool;
        synchronized (pool) {
            if (scratchIndex > 0) {
                pool[--scratchIndex] = snap;
            }
        }
    }
}
