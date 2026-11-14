package dev.grimml.grim;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// preallocated mpsc ring netty offers primitives only
public final class FlagQueue {

    public static final class Slot {
        public long anonId;
        public long sessionId;
        public long nanos;
        public long joinNanos;
        public long teleportNanos;
        public long worldNanos;
        public long respawnNanos;
        public long setbackNanos;
        public int checkHash;
        public int tick;
        public int ping;
        public int jitter;
        public int validity;
        public int cancelled;
        final AtomicInteger ready = new AtomicInteger(0);
    }

    private final Slot[] slots;
    private final AtomicInteger write = new AtomicInteger(0);
    private final AtomicInteger read = new AtomicInteger(0);
    private final AtomicLong drops = new AtomicLong(0);
    private final int cap;
    private final int mask;

    public FlagQueue(int cap) {
        int n = 1;
        while (n < cap) {
            n <<= 1;
        }
        this.cap = n;
        this.mask = n - 1;
        this.slots = new Slot[n];
        for (int i = 0; i < n; i++) {
            slots[i] = new Slot();
        }
    }

    public boolean offer(
            long anonId, long sessionId, int checkHash, int tick, long nanos,
            int ping, int jitter, int validity, int cancelled,
            long joinNanos, long teleportNanos, long worldNanos, long respawnNanos, long setbackNanos
    ) {
        int w;
        int r;
        do {
            w = write.get();
            r = read.get();
            if (w - r >= cap) {
                drops.incrementAndGet();
                return false;
            }
        } while (!write.compareAndSet(w, w + 1));
        Slot s = slots[w & mask];
        s.anonId = anonId;
        s.sessionId = sessionId;
        s.checkHash = checkHash;
        s.tick = tick;
        s.nanos = nanos;
        s.ping = ping;
        s.jitter = jitter;
        s.validity = validity;
        s.cancelled = cancelled;
        s.joinNanos = joinNanos;
        s.teleportNanos = teleportNanos;
        s.worldNanos = worldNanos;
        s.respawnNanos = respawnNanos;
        s.setbackNanos = setbackNanos;
        s.ready.set(1);
        return true;
    }

    public boolean poll(Slot out) {
        int r = read.get();
        int w = write.get();
        if (r == w) {
            return false;
        }
        Slot s = slots[r & mask];
        if (s.ready.get() != 1) {
            return false;
        }
        out.anonId = s.anonId;
        out.sessionId = s.sessionId;
        out.checkHash = s.checkHash;
        out.tick = s.tick;
        out.nanos = s.nanos;
        out.ping = s.ping;
        out.jitter = s.jitter;
        out.validity = s.validity;
        out.cancelled = s.cancelled;
        out.joinNanos = s.joinNanos;
        out.teleportNanos = s.teleportNanos;
        out.worldNanos = s.worldNanos;
        out.respawnNanos = s.respawnNanos;
        out.setbackNanos = s.setbackNanos;
        s.ready.set(0);
        read.incrementAndGet();
        return true;
    }

    public long drops() {
        return drops.get();
    }

    public int capacity() {
        return cap;
    }
}
