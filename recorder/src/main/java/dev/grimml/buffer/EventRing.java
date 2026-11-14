package dev.grimml.buffer;

import java.util.concurrent.atomic.AtomicIntegerArray;

// seqlock event ring payload is primitives only
public final class EventRing {

    public final int cap;
    private final AtomicIntegerArray seq;
    public final byte[] type;
    public final int[] tick;
    public final int[] nsOffset;
    public final byte[] order;
    public final long[] nanos;
    public final long[] a;
    public final int[] b;
    public final int[] c;
    public final int[] d;
    public final int[] e;
    public final int[] f;
    public final short[] ping;
    private int write;
    private int count;
    private int overflows;

    public EventRing(int cap) {
        this.cap = cap;
        this.seq = new AtomicIntegerArray(cap);
        this.type = new byte[cap];
        this.tick = new int[cap];
        this.nsOffset = new int[cap];
        this.order = new byte[cap];
        this.nanos = new long[cap];
        this.a = new long[cap];
        this.b = new int[cap];
        this.c = new int[cap];
        this.d = new int[cap];
        this.e = new int[cap];
        this.f = new int[cap];
        this.ping = new short[cap];
    }

    public void write(byte t, int tk, int nsOff, int ord, long nano, long aa, int bb, int cc, int dd, int ee, int ff, int pingMs) {
        int i = write;
        int s = seq.get(i);
        seq.set(i, s | 1);
        type[i] = t;
        tick[i] = tk;
        nsOffset[i] = nsOff;
        order[i] = (byte) ord;
        nanos[i] = nano;
        a[i] = aa;
        b[i] = bb;
        c[i] = cc;
        d[i] = dd;
        e[i] = ee;
        f[i] = ff;
        ping[i] = (short) Math.min(Math.max(pingMs, 0), Short.MAX_VALUE);
        seq.set(i, (s | 1) + 1);
        write = (i + 1) % cap;
        if (count < cap) {
            count++;
        } else {
            overflows++;
        }
    }

    public int count() {
        return count;
    }

    public int writeIndex() {
        return write;
    }

    public int overflows() {
        return overflows;
    }

    public boolean copySlot(int i, EventOut out) {
        int s1 = seq.get(i);
        if ((s1 & 1) != 0) {
            return false;
        }
        out.type = type[i];
        out.tick = tick[i];
        out.nsOffset = nsOffset[i];
        out.order = order[i];
        out.nanos = nanos[i];
        out.a = a[i];
        out.b = b[i];
        out.c = c[i];
        out.d = d[i];
        out.e = e[i];
        out.f = f[i];
        out.ping = ping[i];
        int s2 = seq.get(i);
        return s1 == s2 && (s2 & 1) == 0;
    }

    public static final class EventOut {
        public byte type, order;
        public int tick, nsOffset, b, c, d, e, f;
        public long nanos, a;
        public short ping;
    }
}
