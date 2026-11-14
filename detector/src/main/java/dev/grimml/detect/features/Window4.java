package dev.grimml.detect.features;

/**
 * One 8 s window with the schema 4 data the features need, laid out like the decoded training windows
 * (training/grimml_train/decode/gml_common.py). Filled live by PlayerTrack, or from the parity fixture in tests.
 */
public final class Window4 {

    // event types: the recorder's RecordTypes numbers
    public static final int ATTACK = 2, SWING = 7, HOTBAR_SWAP = 9, BLOCK_PLACE = 11, KNOCKBACK = 12, RTT = 21,
            INPUT = 27, MOVE_STATE = 28, ENTITY_SENT = 29, RTT_SENT = 30, ENTITY_SPAWN = 31;
    public static final int F_ON_GROUND = 1, F_SNEAK = 1 << 7, F_SPRINT = 1 << 8;
    public static final int OPP_BLOCKING = 1 << 3;

    // frames
    public int n;
    public final int[] tick, flags, clientTick;
    public final boolean[] hasRot, hasOpp;
    public final float[] yawExact, pitchExact;
    public final int[] oppDx, oppDy, oppDz, oppState, oppBlockAgo, oppHurtAgo, maxGap, burst;

    // events (only the types above are needed)
    public int m;
    public final int[] eType, eTick, eB, eC, eD, eG, eClientTick;
    public final long[] eNanos, eA;

    public Window4(int frameCap, int eventCap) {
        tick = new int[frameCap];
        flags = new int[frameCap];
        clientTick = new int[frameCap];
        hasRot = new boolean[frameCap];
        hasOpp = new boolean[frameCap];
        yawExact = new float[frameCap];
        pitchExact = new float[frameCap];
        oppDx = new int[frameCap];
        oppDy = new int[frameCap];
        oppDz = new int[frameCap];
        oppState = new int[frameCap];
        oppBlockAgo = new int[frameCap];
        oppHurtAgo = new int[frameCap];
        maxGap = new int[frameCap];
        burst = new int[frameCap];
        eType = new int[eventCap];
        eTick = new int[eventCap];
        eB = new int[eventCap];
        eC = new int[eventCap];
        eD = new int[eventCap];
        eG = new int[eventCap];
        eClientTick = new int[eventCap];
        eNanos = new long[eventCap];
        eA = new long[eventCap];
    }

    public int frameCap() {
        return tick.length;
    }

    public int eventCap() {
        return eType.length;
    }
}
