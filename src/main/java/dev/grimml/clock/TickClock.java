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
    private int ownTick;
    private double intervalEma;
    // folia has no global tick time or tps so mspt stays 0 and the mspt guard never trips there
    private boolean serverStats = !dev.grimml.scheduler.Tasks.FOLIA;

    public void onTick() {
        long now = System.nanoTime();
        long prev = tickStartNanos;
        if (prev != 0L) {
            long dur = (now - prev) / 1_000_000L;
            lastDurationMs = dur > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) dur;
        }
        tickStartNanos = now;
        ownTick++;
        if (!serverStats) {
            tick = ownTick;
            int dur = lastDurationMs;
            intervalEma = intervalEma == 0.0 ? 50.0 : intervalEma * 0.95 + Math.max(dur, 1) * 0.05;
            tps = Math.min(20.0, 1000.0 / Math.max(50.0, intervalEma));
            return;
        }
        try {
            tick = Bukkit.getCurrentTick();
            mspt = Bukkit.getAverageTickTime();
            tps = Bukkit.getTPS()[0];
        } catch (UnsupportedOperationException e) {
            serverStats = false;
            tick = ownTick;
        }
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
