package dev.grimml.detect.features;

/**
 * The live LungeRate check for one player (exact port of Python spearmace.LiveLunge). Not thread safe: one scan
 * thread feeds it.
 *
 * A swap-lunge is a spear STAB whose last hotbar swap went to the spear at most swapInMs earlier (each swap counts
 * once). By hand the chain swap -> jab -> swap back takes time: ~700 recorded legit swap-lunges were never under
 * 345 ms apart, while Instant Lunge chains them 100-250 ms apart. Gaps in [minMs, fastMs) between consecutive
 * swap-lunges are counted, and the check alerts when {@code count} of them fall inside windowMs. Gaps under minMs are
 * packets bunched by lag and do not count. All times are integer nanoseconds.
 */
public final class LungeRate {

    public static final int SPEAR = 4;

    private final long fastNs, minNs, windowNs, swapInNs;
    private final int count;
    private final long[] fast;
    private int head, size;

    private int swapTo = -1;
    private long swapNs;
    private boolean swapUsed = true;
    private boolean hasLunge;
    private long lastLunge;
    private int lunges;
    /** Event time of the last event fed, so the scanner feeds each event once. */
    public long lastNanos = Long.MIN_VALUE;

    public LungeRate(double fastMs, double minMs, int count, double windowMs, double swapInMs) {
        this.fastNs = (long) (fastMs * 1e6);
        this.minNs = (long) (minMs * 1e6);
        this.count = Math.max(1, count);
        this.windowNs = (long) (windowMs * 1e6);
        this.swapInNs = (long) (swapInMs * 1e6);
        this.fast = new long[this.count];
    }

    public void swap(long nanos, int toCat) {
        swapTo = toCat;
        swapNs = nanos;
        swapUsed = false;
    }

    /** Feeds one STAB; true when the check alerts (the fast-gap count then restarts). */
    public boolean stab(long nanos) {
        if (swapUsed || swapTo != SPEAR || nanos - swapNs > swapInNs) {
            return false;
        }
        swapUsed = true;
        lunges++;
        boolean had = hasLunge;
        long prev = lastLunge;
        hasLunge = true;
        lastLunge = nanos;
        if (!had) {
            return false;
        }
        long gap = nanos - prev;
        if (gap >= minNs && gap < fastNs) {
            if (size == fast.length) {   // full: the oldest is dropped (only reachable if count was just hit)
                head = (head + 1) % fast.length;
                size--;
            }
            fast[(head + size) % fast.length] = nanos;
            size++;
        }
        while (size > 0 && nanos - fast[head] > windowNs) {
            head = (head + 1) % fast.length;
            size--;
        }
        if (size >= count) {
            head = 0;
            size = 0;
            return true;
        }
        return false;
    }

    public int lunges() {
        return lunges;
    }

    public int recentFast() {
        return size;
    }
}
