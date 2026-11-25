package dev.grimml.detect.features;

/**
 * This is a feature that detects if the player is using a lunge.
 * 
 * A lunge is a spear stab whose last hotbar swap went to the spear at most swapInMs earlier (each swap counts once) while jabbing while holding the spear never counts.
 * 
 * It works by checking the gaps in between consecutive lunges and seeing if they are within the minMs and fastMs range.
 * It also checks if the player is holding the spear exactly one client tick after the last hit with it.
 * It also checks if the player is swapping in the same client tick after an attack or stab, which vanilla never sends (hotbar changes go before attacks inside a tick) ther is one mod which false flags but i guess we can just call it it a cheat.
 * It also checks if the player is swapping away from the axe one client tick after the last hit with it.

 */
public final class LungeRate {

    public static final int AXE = 2, SPEAR = 4;
    public static final int RATE = 1, HOLD = 2, ORDER = 4, AXE_HOLD = 8;

    private final long fastNs, minNs, windowNs, swapInNs, orderWindowNs;
    private final int count, holdCount, holdOf, orderCount, axeCount, axeOf;

    private final LongRing fast;
    private final LongRing recent = new LongRing(64);   
    // evidence behind the last alert of each check (0-1): what share of recent actions looked like a cheat
    public double rateShare, holdShare, axeShare;
    private final LongRing order;
    private final int[] holds;
    private int holdHead, holdSize, holdSum;
    private final int[] axes;
    private int axeHead, axeSize, axeSum, axeHolds;
    private int axeHitCt = -1; // saving the last axe hit time

    private int swapTo = -1;
    private long swapNs;
    private int swapCt;
    private boolean swapUsed = true;
    private boolean hasLunge;
    private long lastLunge;
    private int lunges, orderTotal;
    private int pendingHoldCt = -1;
    private int hitCt;
    // saving the last event time
    public long lastNanos = Long.MIN_VALUE;

    public LungeRate(double fastMs, double minMs, int count, double windowMs, double swapInMs,
                     int holdCount, int holdOf, int orderCount, double orderWindowMs) {
        this(fastMs, minMs, count, windowMs, swapInMs, holdCount, holdOf, orderCount, orderWindowMs, 6, 10);
    }

    public LungeRate(double fastMs, double minMs, int count, double windowMs, double swapInMs,
                     int holdCount, int holdOf, int orderCount, double orderWindowMs, int axeCount, int axeOf) {
        this.fastNs = (long) (fastMs * 1e6);
        this.minNs = (long) (minMs * 1e6);
        this.count = count;
        this.windowNs = (long) (windowMs * 1e6);
        this.swapInNs = (long) (swapInMs * 1e6);
        this.holdCount = holdCount;
        this.holdOf = Math.max(1, holdOf);
        this.orderCount = orderCount;
        this.orderWindowNs = (long) (orderWindowMs * 1e6);
        this.fast = new LongRing(Math.max(1, count) + 1);
        this.order = new LongRing(Math.max(1, orderCount) + 1);
        this.holds = new int[this.holdOf + 1];
        this.axeCount = axeCount;
        this.axeOf = Math.max(1, axeOf);
        this.axes = new int[this.axeOf + 1];
    }

    public LungeRate() {
        this(300.0, 75.0, 6, 30000.0, 150.0, 5, 10, 2, 60000.0);
    }

    public int swap(long nanos, int toCat, int fromCat, int ct) {
        int out = 0;
        if (ct > 0 && hitCt == ct) {
            orderTotal++;
            order.add(nanos);
            while (order.size > 0 && nanos - order.first() > orderWindowNs) {
                order.drop();
            }
            if (order.size >= orderCount) {
                order.clear();
                out |= ORDER;
            }
        }
        if (pendingHoldCt >= 0) {
            if (fromCat == SPEAR && ct > 0 && pendingHoldCt > 0) {
                int h = ct - pendingHoldCt == 1 ? 1 : 0;
                holds[(holdHead + holdSize) % holds.length] = h;
                holdSize++;
                holdSum += h;
                if (holdSize > holdOf) {
                    holdSum -= holds[holdHead];
                    holdHead = (holdHead + 1) % holds.length;
                    holdSize--;
                }
                if (holdSum >= holdCount) {
                    holdShare = (double) holdSum / holdSize;
                    holdHead = 0;
                    holdSize = 0;
                    holdSum = 0;
                    out |= HOLD;
                }
            }
            pendingHoldCt = -1;
        }
        if (axeHitCt > 0) {
            if (fromCat == AXE && ct > 0) {
                axeHolds++;
                int h = ct - axeHitCt == 1 ? 1 : 0;
                axes[(axeHead + axeSize) % axes.length] = h;
                axeSize++;
                axeSum += h;
                if (axeSize > axeOf) {
                    axeSum -= axes[axeHead];
                    axeHead = (axeHead + 1) % axes.length;
                    axeSize--;
                }
                if (axeSum >= axeCount) {
                    axeShare = (double) axeSum / axeSize;
                    axeHead = 0;
                    axeSize = 0;
                    axeSum = 0;
                    out |= AXE_HOLD;
                }
            }
            axeHitCt = -1;
        }
        swapTo = toCat;
        swapNs = nanos;
        swapCt = ct;
        swapUsed = false;
        return out;
    }

    public int hit(long nanos, int ct) {
        hitCt = ct;
        if (swapTo == AXE && ct > 0) {
            axeHitCt = ct;
        }
        return 0;
    }

    /** Feeds one STAB; returns RATE when LungeRate alerts. */
    public int stab(long nanos, int ct) {
        hitCt = ct;
        if (swapUsed || swapTo != SPEAR || nanos - swapNs > swapInNs) {
            return 0;
        }
        swapUsed = true;
        lunges++;
        pendingHoldCt = swapCt;
        boolean had = hasLunge;
        long prev = lastLunge;
        hasLunge = true;
        lastLunge = nanos;
        recent.add(nanos);
        while (recent.size > 0 && nanos - recent.first() > windowNs) {
            recent.drop();
        }
        if (!had) {
            return 0;
        }
        long gap = nanos - prev;
        if (gap >= minNs && gap < fastNs) {
            fast.add(nanos);
        }
        while (fast.size > 0 && nanos - fast.first() > windowNs) {
            fast.drop();
        }
        if (fast.size >= count) {
            rateShare = Math.min(1.0, (double) fast.size / Math.max(1, recent.size - 1));
            fast.clear();
            return RATE;
        }
        return 0;
    }

    public int lunges() {
        return lunges;
    }

    public int axeHolds() {
        return axeHolds;
    }

    public int orderTotal() {
        return orderTotal;
    }

    public int recentFast() {
        return fast.size;
    }

    // a small FIFO of times
    private static final class LongRing {
        final long[] v; // the array of times
        int head, size;

        LongRing(int cap) {
            v = new long[cap];
        }

        void add(long x) {
            if (size == v.length) {
                drop(); // dropping the first time if the array is full
            }
            v[(head + size) % v.length] = x;
            size++;
        }

        long first() {
            return v[head];
        }

        void drop() {
            head = (head + 1) % v.length;
            size--; // dropping the first time
        }

        void clear() {
            head = 0;
            size = 0;
        }
    }
}
