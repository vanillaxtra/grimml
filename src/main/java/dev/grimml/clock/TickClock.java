package dev.grimml.clock;

import org.bukkit.Bukkit;

import java.util.concurrent.ThreadLocalRandom;

// main thread updates these each tick netty only reads volatiles
public final class TickClock {

    public final long bootId = ThreadLocalRandom.current().nextLong() ^ System.nanoTime();
    public final long bootEpochMs = System.currentTimeMillis();
    public final long bootNanos = System.nanoTime();

    private volatile int tick;
    private volatile long tickStartNanos;
    private volatile int lastDurationMs;
    private volatile double mspt;
    private volatile double tps;

    public void onTick() {
        long now = System.nanoTime();
        long prev = tickStartNanos;
        if (prev != 0L) {
            long dur = (now - prev) / 1_000_000L;
            lastDurationMs = dur > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) dur;
        }
        tickStartNanos = now;
        tick = Bukkit.getCurrentTick();
        mspt = Bukkit.getAverageTickTime();
        tps = Bukkit.getTPS()[0];
    }

    public int tick() {
        return tick;
    }

    public long tickStartNanos() {
        return tickStartNanos;
    }

    public int lastDurationMs() {
        return lastDurationMs;
    }

    public double mspt() {
        return mspt;
    }

    public double tps() {
        return tps;
    }

    public int nsOffset(long nanos) {
        long off = nanos - tickStartNanos;
        if (off <= 0L) {
            return 0;
        }
        if (off > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) off;
    }

    public long epochMsForNanos(long nanos) {
        return bootEpochMs + (nanos - bootNanos) / 1_000_000L;
    }
}
