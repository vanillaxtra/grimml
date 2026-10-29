package dev.grimml.buffer;

import dev.grimml.format.RecordTypes;

public final class PlayerRingBuffer {

    public final long sessionId;
    public final long anonPlayerId;
    public final int ringCap;
    public final int eventCap;

    public final int[] rotTick;
    public final float[] yaw;
    public final float[] pitch;
    public final long[] rotNanos;

    public final byte[] eventType;
    public final int[] eventTick;
    public final long[] eventNanos;
    public final int[] eventA;
    public final int[] eventB;
    public final int[] eventC;
    public final short[] eventPing;

    private int rotWrite;
    private int rotCount;
    private int eventWrite;
    private int eventCount;

    public volatile int stateFlags;
    public volatile int pingMs;
    public volatile long lastAttackNanos;
    public volatile long combatActiveUntilNanos;

    public volatile String clientBrand = "";
    public volatile int clientProtocol;
    public volatile boolean bedrock;

    private final Object snapLock = new Object();

    public PlayerRingBuffer(long sessionId, long anonPlayerId, int ringCap, int eventCap) {
        this.sessionId = sessionId;
        this.anonPlayerId = anonPlayerId;
        this.ringCap = ringCap;
        this.eventCap = eventCap;
        this.rotTick = new int[ringCap];
        this.yaw = new float[ringCap];
        this.pitch = new float[ringCap];
        this.rotNanos = new long[ringCap];
        this.eventType = new byte[eventCap];
        this.eventTick = new int[eventCap];
        this.eventNanos = new long[eventCap];
        this.eventA = new int[eventCap];
        this.eventB = new int[eventCap];
        this.eventC = new int[eventCap];
        this.eventPing = new short[eventCap];
    }

    public void writeRotation(int tick, float y, float p, long nanos) {
        int i = rotWrite;
        rotTick[i] = tick;
        yaw[i] = y;
        pitch[i] = p;
        rotNanos[i] = nanos;
        rotWrite = (i + 1) % ringCap;
        if (rotCount < ringCap) {
            rotCount++;
        }
    }

    public void writeEvent(byte type, int tick, long nanos, int a, int b, int c, int ping) {
        int i = eventWrite;
        eventType[i] = type;
        eventTick[i] = tick;
        eventNanos[i] = nanos;
        eventA[i] = a;
        eventB[i] = b;
        eventC[i] = c;
        eventPing[i] = (short) Math.min(Short.MAX_VALUE, Math.max(0, ping));
        eventWrite = (i + 1) % eventCap;
        if (eventCount < eventCap) {
            eventCount++;
        }
    }

    public void markAttack(long nowNanos, long postNanos) {
        lastAttackNanos = nowNanos;
        long until = nowNanos + postNanos;
        if (until > combatActiveUntilNanos) {
            combatActiveUntilNanos = until;
        }
    }

    public boolean isCombatActive(long nowNanos) {
        return nowNanos <= combatActiveUntilNanos;
    }

    // copy combat-window samples into out returns false if nothing to flush
    
    public boolean trySnapshot(CombatSnapshot out, long nowNanos, long preNanos) {
        long lastAttack = lastAttackNanos;
        if (lastAttack == 0L) {
            return false;
        }
        if (!isCombatActive(nowNanos) && nowNanos > lastAttack + preNanos) {
            return false;
        }
        long windowStart = Math.max(0L, lastAttack - preNanos);

        synchronized (snapLock) {
            out.reset();
            out.sessionId = sessionId;
            out.anonPlayerId = anonPlayerId;
            out.stateFlags = stateFlags | (bedrock ? RecordTypes.FLAG_BEDROCK : 0);
            out.pingMs = pingMs;
            out.clientBrand = clientBrand;
            out.clientProtocol = clientProtocol;
            out.windowStartNanos = windowStart;
            out.windowEndNanos = nowNanos;

            int rc = rotCount;
            int rw = rotWrite;
            int rStart = (rw - rc + ringCap) % ringCap;
            for (int n = 0; n < rc; n++) {
                int i = (rStart + n) % ringCap;
                long t = rotNanos[i];
                if (t < windowStart || t > nowNanos) {
                    continue;
                }
                if (!out.addRotation(rotTick[i], yaw[i], pitch[i], t)) {
                    break;
                }
            }

            int ec = eventCount;
            int ew = eventWrite;
            int eStart = (ew - ec + eventCap) % eventCap;
            for (int n = 0; n < ec; n++) {
                int i = (eStart + n) % eventCap;
                long t = eventNanos[i];
                if (t < windowStart || t > nowNanos) {
                    continue;
                }
                if (!out.addEvent(eventType[i], eventTick[i], t, eventA[i], eventB[i], eventC[i], eventPing[i])) {
                    break;
                }
            }

            return out.rotCount > 0 || out.eventCount > 0;
        }
    }
}
