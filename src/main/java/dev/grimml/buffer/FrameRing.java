package dev.grimml.buffer;

import java.util.concurrent.atomic.AtomicIntegerArray;

// seqlock per slot writer never waits reader discards torn frames
public final class FrameRing {

    public static final int F_ON_GROUND = 1;
    public static final int F_HORIZ_COLL = 1 << 1;
    public static final int F_INPUT_W = 1 << 2;
    public static final int F_INPUT_A = 1 << 3;
    public static final int F_INPUT_S = 1 << 4;
    public static final int F_INPUT_D = 1 << 5;
    public static final int F_JUMP = 1 << 6;
    public static final int F_SNEAK = 1 << 7;
    public static final int F_SPRINT = 1 << 8;
    public static final int F_INPUT_INFERRED = 1 << 9;
    public static final int F_CLIENT_TICK_END = 1 << 10;
    public static final int F_JUMP_EDGE = 1 << 11;

    public final int cap;
    private final AtomicIntegerArray seq;
    public final int[] tick;
    public final int[] nsOffset;
    public final byte[] order;
    public final short[] tickDur;
    public final int[] xMilli;
    public final int[] yMilli;
    public final int[] zMilli;
    public final int[] yawCenti;
    public final int[] pitchCenti;
    public final int[] flags;
    public final byte[] pose;
    public final byte[] heldSlot;
    public final byte[] heldCat;
    public final int[] heldId;
    public final byte[] offCat;
    public final int[] offId;
    public final byte[] packets;
    public final short[] ping;
    public final short[] jitter;
    public final int[] ackTx;
    public final int[] lastGroundY;
    public final int[] fallMilli;
    public final int[] vehicleType;
    public final int[] vehicleId;

    private int write;
    private int count;
    private int overflows;

    public FrameRing(int cap) {
        this.cap = cap;
        this.seq = new AtomicIntegerArray(cap);
        this.tick = new int[cap];
        this.nsOffset = new int[cap];
        this.order = new byte[cap];
        this.tickDur = new short[cap];
        this.xMilli = new int[cap];
        this.yMilli = new int[cap];
        this.zMilli = new int[cap];
        this.yawCenti = new int[cap];
        this.pitchCenti = new int[cap];
        this.flags = new int[cap];
        this.pose = new byte[cap];
        this.heldSlot = new byte[cap];
        this.heldCat = new byte[cap];
        this.heldId = new int[cap];
        this.offCat = new byte[cap];
        this.offId = new int[cap];
        this.packets = new byte[cap];
        this.ping = new short[cap];
        this.jitter = new short[cap];
        this.ackTx = new int[cap];
        this.lastGroundY = new int[cap];
        this.fallMilli = new int[cap];
        this.vehicleType = new int[cap];
        this.vehicleId = new int[cap];
    }

    public void write(
            int t, int nsOff, int ord, int durMs,
            int x, int y, int z, int yaw, int pitch,
            int fl, int poseVal,
            int slot, int hCat, int hId, int oCat, int oId,
            int pktCount, int pingMs, int jitterMs, int ack,
            int groundY, int fall, int vehType, int vehId
    ) {
        int i = write;
        int s = seq.get(i);
        seq.set(i, s | 1);
        tick[i] = t;
        nsOffset[i] = nsOff;
        order[i] = (byte) ord;
        tickDur[i] = (short) Math.min(durMs, Short.MAX_VALUE);
        xMilli[i] = x;
        yMilli[i] = y;
        zMilli[i] = z;
        yawCenti[i] = yaw;
        pitchCenti[i] = pitch;
        flags[i] = fl;
        pose[i] = (byte) poseVal;
        heldSlot[i] = (byte) slot;
        heldCat[i] = (byte) hCat;
        heldId[i] = hId;
        offCat[i] = (byte) oCat;
        offId[i] = oId;
        packets[i] = (byte) Math.min(pktCount, 127);
        ping[i] = (short) Math.min(Math.max(pingMs, 0), Short.MAX_VALUE);
        jitter[i] = (short) Math.min(Math.max(jitterMs, 0), Short.MAX_VALUE);
        ackTx[i] = ack;
        lastGroundY[i] = groundY;
        fallMilli[i] = fall;
        vehicleType[i] = vehType;
        vehicleId[i] = vehId;
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

    public boolean copySlot(int i, FrameOut out) {
        int s1 = seq.get(i);
        if ((s1 & 1) != 0) {
            return false;
        }
        out.tick = tick[i];
        out.nsOffset = nsOffset[i];
        out.order = order[i];
        out.tickDur = tickDur[i];
        out.xMilli = xMilli[i];
        out.yMilli = yMilli[i];
        out.zMilli = zMilli[i];
        out.yawCenti = yawCenti[i];
        out.pitchCenti = pitchCenti[i];
        out.flags = flags[i];
        out.pose = pose[i];
        out.heldSlot = heldSlot[i];
        out.heldCat = heldCat[i];
        out.heldId = heldId[i];
        out.offCat = offCat[i];
        out.offId = offId[i];
        out.packets = packets[i];
        out.ping = ping[i];
        out.jitter = jitter[i];
        out.ackTx = ackTx[i];
        out.lastGroundY = lastGroundY[i];
        out.fallMilli = fallMilli[i];
        out.vehicleType = vehicleType[i];
        out.vehicleId = vehicleId[i];
        int s2 = seq.get(i);
        return s1 == s2 && (s2 & 1) == 0;
    }

    public static final class FrameOut {
        public int tick, nsOffset, flags, heldId, offId, ackTx, lastGroundY, fallMilli, vehicleType, vehicleId;
        public int xMilli, yMilli, zMilli, yawCenti, pitchCenti;
        public short tickDur, ping, jitter;
        public byte order, pose, heldSlot, heldCat, offCat, packets;
    }
}
