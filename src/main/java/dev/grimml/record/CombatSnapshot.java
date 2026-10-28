package dev.grimml.record;


public final class CombatSnapshot {

    public long sessionId;
    public long anonPlayerId;
    public int stateFlags;
    public int pingMs;
    public String clientBrand = "";
    public int clientProtocol;
    public long windowStartNanos;
    public long windowEndNanos;

    public int rotCount;
    public final int[] rotTick;
    public final float[] yaw;
    public final float[] pitch;
    public final long[] rotNanos;

    public int eventCount;
    public final byte[] eventType;
    public final int[] eventTick;
    public final long[] eventNanos;
    public final int[] eventA;
    public final int[] eventB;
    public final int[] eventC;
    public final short[] eventPing;

    public CombatSnapshot(int rotCap, int eventCap) {
        rotTick = new int[rotCap];
        yaw = new float[rotCap];
        pitch = new float[rotCap];
        rotNanos = new long[rotCap];
        eventType = new byte[eventCap];
        eventTick = new int[eventCap];
        eventNanos = new long[eventCap];
        eventA = new int[eventCap];
        eventB = new int[eventCap];
        eventC = new int[eventCap];
        eventPing = new short[eventCap];
    }

    public void reset() {
        rotCount = 0;
        eventCount = 0;
        clientBrand = "";
    }

    public boolean addRotation(int tick, float y, float p, long nanos) {
        if (rotCount >= rotTick.length) {
            return false;
        }
        int i = rotCount++;
        rotTick[i] = tick;
        yaw[i] = y;
        pitch[i] = p;
        rotNanos[i] = nanos;
        return true;
    }

    public boolean addEvent(byte type, int tick, long nanos, int a, int b, int c, int ping) {
        if (eventCount >= eventType.length) {
            return false;
        }
        int i = eventCount++;
        eventType[i] = type;
        eventTick[i] = tick;
        eventNanos[i] = nanos;
        eventA[i] = a;
        eventB[i] = b;
        eventC[i] = c;
        eventPing[i] = (short) ping;
        return true;
    }
}
