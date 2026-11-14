package dev.grimml.detect.track;

/**
 * Per-player rings, written by that player's netty thread, read by the scan thread.
 * Frame sequencing copies the recorder (recorder/.../PlayerRingBuffer: nextOrder, applyFlying, markClientTickEnd,
 * flushOpenFrame) so live windows contain the same frames the training windows did:
 * one frame per server tick that saw a packet, holding the state at the moment it is flushed.
 * Only the fields the model uses are kept. All methods are synchronized: the lock is per player and
 * uncontended except for the scan thread's copy every couple of seconds.
 */
public final class PlayerTrack {

    private static final int NONE = Integer.MIN_VALUE;

    public final int entityId;

    private final int cap;
    private final int[] fYaw;
    private final int[] fPitch;
    private final int[] fX;
    private final int[] fZ;
    private final int[] fFlags;
    private int fWrite;
    private int fCount;

    private final long[] attacks;
    private int aWrite;
    private int aCount;

    private int xMilli;
    private int zMilli;
    private int yawCenti;
    private int pitchCenti;
    private int flags;
    private int openTick = NONE;
    private int orderTick = 0;

    private volatile long lastAttackNanos;

    public PlayerTrack(int entityId, int frameCap, int attackCap) {
        this.entityId = entityId;
        this.cap = frameCap;
        this.fYaw = new int[frameCap];
        this.fPitch = new int[frameCap];
        this.fX = new int[frameCap];
        this.fZ = new int[frameCap];
        this.fFlags = new int[frameCap];
        this.attacks = new long[attackCap];
    }

    public long lastAttackNanos() {
        return lastAttackNanos;
    }

    /** Any recorder-visible packet: a new server tick commits the frame left open by an older tick. */
    public synchronized void packet(int tick) {
        if (orderTick != tick) {
            if (openTick != NONE && openTick != tick) {
                flush();
            }
            orderTick = tick;
            openTick = tick;
        }
    }

    public synchronized void flying(int tick, double x, double z, float yaw, float pitch, boolean onGround,
                                    boolean hasPos, boolean hasRot) {
        packet(tick);
        if (hasPos) {
            xMilli = (int) Math.round(x * 1000);
            zMilli = (int) Math.round(z * 1000);
        }
        if (hasRot) {
            yawCenti = (int) Math.round(yaw * 100);
            pitchCenti = (int) Math.round(pitch * 100);
        }
        flags = onGround ? (flags | 1) : (flags & ~1);
        if (openTick != NONE && openTick != tick) {
            flush();
        }
        openTick = tick;
    }

    public synchronized void clientTickEnd() {
        flush();
    }

    public synchronized void attack(int tick, long nanos) {
        packet(tick);
        attacks[aWrite] = nanos;
        aWrite = (aWrite + 1) % attacks.length;
        aCount = Math.min(aCount + 1, attacks.length);
        lastAttackNanos = nanos;
    }

    private void flush() {
        if (openTick == NONE) {
            return;
        }
        fYaw[fWrite] = yawCenti;
        fPitch[fWrite] = pitchCenti;
        fX[fWrite] = xMilli;
        fZ[fWrite] = zMilli;
        fFlags[fWrite] = flags;
        fWrite = (fWrite + 1) % cap;
        fCount = Math.min(fCount + 1, cap);
        openTick = NONE;
    }

    /** Copies the whole frame ring (oldest first) and the attacks in [fromNanos, toNanos] into w. */
    public synchronized void snapshot(Window w, long fromNanos, long toNanos) {
        w.n = fCount;
        int start = (fWrite - fCount + cap) % cap;
        for (int k = 0; k < fCount; k++) {
            int i = (start + k) % cap;
            w.yaw[k] = fYaw[i];
            w.pitch[k] = fPitch[i];
            w.x[k] = fX[i];
            w.z[k] = fZ[i];
            w.flags[k] = fFlags[i];
        }
        w.m = 0;
        int aStart = (aWrite - aCount + attacks.length) % attacks.length;
        for (int k = 0; k < aCount; k++) {
            long t = attacks[(aStart + k) % attacks.length];
            if (t >= fromNanos && t <= toNanos) {
                w.attacks[w.m++] = t;
            }
        }
    }

    /** Reusable buffers for one scan. */
    public static final class Window {
        public final int[] yaw;
        public final int[] pitch;
        public final int[] x;
        public final int[] z;
        public final int[] flags;
        public final long[] attacks;
        public int n;
        public int m;

        public Window(int frameCap, int attackCap) {
            yaw = new int[frameCap];
            pitch = new int[frameCap];
            x = new int[frameCap];
            z = new int[frameCap];
            flags = new int[frameCap];
            attacks = new long[attackCap];
        }
    }
}
