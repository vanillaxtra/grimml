package dev.grimml.detect.track;

import dev.grimml.detect.features.Window4;

import java.util.function.IntFunction;

/**
 * Per-player rings, written by that player's netty thread, read by the scan thread.
 * Frame sequencing copies the recorder (recorder/.../PlayerRingBuffer: nextOrder, applyFlying, markClientTickEnd,
 * flushOpenFrame) so live windows contain the same frames the training windows did: one frame per server tick that saw
 * a packet, holding the state at the moment it is flushed. Schema 4 data (docs/schema4.md) is kept the same way:
 * exact rotation, client tick, the opponent block and packet timing per frame, and the events the features use.
 * All netty-side methods are synchronized on this track: the lock is per player and only contended by the scan
 * thread's copy every couple of seconds. Fields other players' threads read are volatile.
 */
public final class PlayerTrack {

    private static final int NONE = Integer.MIN_VALUE;
    // live flags
    public static final int L_SPRINT = 1, L_SNEAK = 2, L_BLOCKING = 4, L_GLIDING = 8, L_USING = 16;
    // item categories (the recorder's ItemCategories numbers that the features look at)
    public static final int CAT_AXE = 2, CAT_MACE = 3, CAT_SPEAR = 4, CAT_SHIELD = 8, CAT_COBWEB = 15;
    /** Digging action STAB (spear jab), written as a BREAK event like the recorder does. */
    public static final int DIG_STAB = 8;

    public final int entityId;
    private final int cap;

    // ---- frames
    private final int[] fYaw, fPitch, fX, fY, fZ, fFlags, fTick, fClientTick;
    private final boolean[] fHasRot, fHasOpp;
    private final float[] fYawExact, fPitchExact;
    private final int[] fOppDx, fOppDy, fOppDz, fOppState, fOppBlockAgo, fOppHurtAgo, fMaxGap, fBurst;
    private int fWrite;
    private int fCount;

    // ---- events
    private final int[] eType, eTick, eB, eC, eD, eG, eClientTick;
    private final long[] eNanos, eA;
    private int eWrite;
    private int eCount;

    // ---- netty-only state
    private int yawCenti, pitchCenti;
    private float yawExact, pitchExact;
    private boolean hasExactRot;
    private int flags;               // bit 0 on ground
    private int inputBits;           // last PLAYER_INPUT: 32 sneak, 64 sprint
    private int openTick = NONE;
    private int orderTick = 0;
    private int clientTick;
    private long lastInboundNanos;
    private int maxGapMs;
    private int burst;
    private int lastResetTick = NONE;
    private int heldSlot;
    private final int[] crystalIds = new int[32];
    private int crystalWrite;

    // ---- shared with other threads
    public volatile int xMilli, yMilli, zMilli, pubYaw, pubFlags;
    public volatile int liveFlags;
    public volatile long blockChangeNanos, lastHurtNanos, lastHitPacketNanos, lastAttackNanos;
    public volatile int lastTargetEntityId, lastAttackerEntityId, opponentEntityId, nearby;
    public volatile int[] hotbarCats = new int[9];   // main thread refreshes
    public volatile int offCat;
    public volatile Object grimUser;                 // ac.grim.grimac.api.GrimUser (main thread sets)
    public volatile IntFunction<PlayerTrack> peers = id -> null;

    public PlayerTrack(int entityId, int frameCap, int eventCap) {
        this.entityId = entityId;
        this.cap = frameCap;
        fYaw = new int[frameCap];
        fPitch = new int[frameCap];
        fX = new int[frameCap];
        fZ = new int[frameCap];
        fY = new int[frameCap];
        fFlags = new int[frameCap];
        fTick = new int[frameCap];
        fClientTick = new int[frameCap];
        fHasRot = new boolean[frameCap];
        fHasOpp = new boolean[frameCap];
        fYawExact = new float[frameCap];
        fPitchExact = new float[frameCap];
        fOppDx = new int[frameCap];
        fOppDy = new int[frameCap];
        fOppDz = new int[frameCap];
        fOppState = new int[frameCap];
        fOppBlockAgo = new int[frameCap];
        fOppHurtAgo = new int[frameCap];
        fMaxGap = new int[frameCap];
        fBurst = new int[frameCap];
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

    public long lastAttackNanos() {
        return lastAttackNanos;
    }

    // ================================================================ recorder-equivalent capture (netty thread)

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

    /** Every inbound packet: longest silence and bursts (PACKET_TIMING). */
    public synchronized void inbound(long nanos) {
        if (lastInboundNanos != 0L) {
            long gap = nanos - lastInboundNanos;
            maxGapMs = (int) Math.max(maxGapMs, Math.min(gap / 1_000_000L, 65_535L));
            if (gap < 2_000_000L) {
                burst++;
            }
        }
        lastInboundNanos = nanos;
    }

    public synchronized void flying(int tick, double x, double y, double z, float yaw, float pitch, boolean onGround,
                                    boolean hasPos, boolean hasRot) {
        packet(tick);
        if (hasPos) {
            xMilli = (int) Math.round(x * 1000);
            yMilli = (int) Math.round(y * 1000);
            zMilli = (int) Math.round(z * 1000);
        }
        if (hasRot) {
            yawCenti = (int) Math.round(yaw * 100);
            pitchCenti = (int) Math.round(pitch * 100);
            yawExact = yaw;
            pitchExact = pitch;
            hasExactRot = true;
            pubYaw = yawCenti;
        }
        flags = onGround ? (flags | 1) : (flags & ~1);
        if (openTick != NONE && openTick != tick) {
            flush();
        }
        openTick = tick;
    }

    public synchronized void clientTickEnd() {
        flush();
        clientTick++;
    }

    public synchronized void input(int tick, long nanos, int bits) {
        inputBits = bits;
        event(Window4.INPUT, tick, nanos, bits, 0, 0, 0, 0);
    }

    /** 1 sprint start, 2 sprint stop, 3 sneak start, 4 sneak stop. */
    public synchronized void moveState(int tick, long nanos, int code) {
        switch (code) {
            case 1 -> live(L_SPRINT, true);
            case 2 -> live(L_SPRINT, false);
            case 3 -> live(L_SNEAK, true);
            case 4 -> live(L_SNEAK, false);
            default -> {
            }
        }
        event(Window4.MOVE_STATE, tick, nanos, code, 0, 0, 0, 0);
    }

    public synchronized void attack(int tick, long nanos, int targetId) {
        packet(tick);
        PlayerTrack target = peers.apply(targetId);
        int kind = target != null ? 1 : (isCrystal(targetId) ? 2 : 0);
        int g = kind | (cooldownTicks(tick) << 8);
        lastTargetEntityId = targetId;
        lastHitPacketNanos = nanos;
        // c: held category << 8, as the recorder writes it (spear-mace features read the weapon from it)
        event(Window4.ATTACK, tick, nanos, 0, targetId, cat(hotbarCats, heldSlot) << 8, 0, g);
        lastAttackNanos = nanos;
    }

    public synchronized void swing(int tick, long nanos) {
        packet(tick);
        event(Window4.SWING, tick, nanos, 0, 0, 0, 0, 0);
        lastResetTick = tick;
    }

    public synchronized void heldChange(int tick, long nanos, int to) {
        int[] cats = hotbarCats;
        int from = heldSlot;
        int fromCat = cat(cats, from);
        heldSlot = to;
        packet(tick);
        lastResetTick = tick;
        event(Window4.HOTBAR_SWAP, tick, nanos, from, to, fromCat | (cat(cats, to) << 8), 0, 0);
    }

    public synchronized void serverHeld(int slot) {
        heldSlot = slot;
    }

    public synchronized void useItem(int tick, boolean offHand) {
        packet(tick);
        int c = offHand ? offCat : cat(hotbarCats, heldSlot);
        if (c == CAT_SHIELD) {
            live(L_BLOCKING, true);
        }
        live(L_USING, true);
    }

    /** Digging packet: RELEASE ends item use; SWAP_ITEM_WITH_OFFHAND is a hotbar_swap with c = held category;
     *  STAB (1.21.11 spear jab, not an attack packet) is a BREAK event with a = 8. */
    public synchronized void dig(int tick, long nanos, boolean release, boolean swap, boolean stab) {
        packet(tick);
        if (stab) {
            event(Window4.BREAK, tick, nanos, DIG_STAB, 0, 0, 0, 0);
        }
        if (release) {
            live(L_USING | L_BLOCKING, false);
        }
        if (swap) {
            event(Window4.HOTBAR_SWAP, tick, nanos, 40, 40, cat(hotbarCats, heldSlot), offCat, 0);
        }
    }

    public synchronized void place(int tick, long nanos, int blockCategory) {
        packet(tick);
        event(Window4.BLOCK_PLACE, tick, nanos, 0, 0, 0, 0, blockCategory);
    }

    public synchronized void knockback(int tick, long nanos, double vx, double vy, double vz) {
        packet(tick);
        event(Window4.KNOCKBACK, tick, nanos, Math.round(vx * 8000), (int) Math.round(vy * 8000), (int) Math.round(vz * 8000), 0, 0);
    }

    /** kind 1 transaction, 2 keep-alive. Does not open a frame tick (recorder schema 4 behaviour). */
    public synchronized void rttSent(int tick, long nanos, long id, int kind) {
        event(Window4.RTT_SENT, tick, nanos, id, kind, 0, 0, 0);
    }

    /** Transaction reply, from Grim's GrimTransactionReceivedEvent. */
    public synchronized void pong(int tick, long nanos, int id) {
        event(Window4.RTT, tick, nanos, id, 0, 1, 0, 0);
    }

    public synchronized void spawn(int tick, long nanos, int id, int kind, double x, double y, double z) {
        int dx = (int) Math.round(x * 1000) - xMilli;
        int dy = (int) Math.round(y * 1000) - yMilli;
        int dz = (int) Math.round(z * 1000) - zMilli;
        if (Math.abs((long) dx) > 16_000 || Math.abs((long) dy) > 16_000 || Math.abs((long) dz) > 16_000) {
            return;
        }
        if (kind == 1) {
            crystalIds[crystalWrite] = id;
            crystalWrite = (crystalWrite + 1) % crystalIds.length;
        }
        event(Window4.ENTITY_SPAWN, tick, nanos, id, kind, dx, dy, 0);
    }

    public void setLive(int bits, boolean on) {
        synchronized (this) {
            live(bits, on);
        }
    }

    // ================================================================ internals

    private void live(int bits, boolean on) {
        int prev = liveFlags;
        int next = on ? prev | bits : prev & ~bits;
        if (next == prev) {
            return;
        }
        liveFlags = next;
        if (((prev ^ next) & L_BLOCKING) != 0) {
            blockChangeNanos = System.nanoTime();
        }
    }

    private static int cat(int[] cats, int slot) {
        return slot >= 0 && slot < cats.length ? cats[slot] : 0;
    }

    private int cooldownTicks(int tick) {
        return lastResetTick == NONE ? 255 : Math.max(0, Math.min(255, tick - lastResetTick));
    }

    private boolean isCrystal(int id) {
        for (int c : crystalIds) {
            if (c == id && c != 0) {
                return true;
            }
        }
        return false;
    }

    private void event(int type, int tick, long nanos, long a, int b, int c, int d, int g) {
        int i = eWrite;
        eType[i] = type;
        eTick[i] = tick;
        eNanos[i] = nanos;
        eA[i] = a;
        eB[i] = b;
        eC[i] = c;
        eD[i] = d;
        eG[i] = g;
        eClientTick[i] = clientTick;
        eWrite = (i + 1) % eType.length;
        eCount = Math.min(eCount + 1, eType.length);
    }

    private void flush() {
        if (openTick == NONE) {
            return;
        }
        int live = liveFlags;
        int fl = flags;
        if ((inputBits & 32) != 0 || (live & L_SNEAK) != 0) fl |= Window4.F_SNEAK;
        if ((inputBits & 64) != 0 || (live & L_SPRINT) != 0) fl |= Window4.F_SPRINT;
        int i = fWrite;
        fYaw[i] = yawCenti;
        fPitch[i] = pitchCenti;
        fX[i] = xMilli;
        fZ[i] = zMilli;
        fY[i] = yMilli;
        fFlags[i] = fl;
        fTick[i] = openTick;
        fClientTick[i] = clientTick;
        fHasRot[i] = hasExactRot;
        fYawExact[i] = yawExact;
        fPitchExact[i] = pitchExact;
        fMaxGap[i] = maxGapMs;
        fBurst[i] = Math.min(burst, 127);
        fillOpponent(i);
        fWrite = (i + 1) % cap;
        fCount = Math.min(fCount + 1, cap);
        pubFlags = fl;
        maxGapMs = 0;
        burst = 0;
        openTick = NONE;
    }

    // the recorder's PlayerRingBuffer.fillExt; reads the opponent's volatile fields, never their lock
    private void fillOpponent(int i) {
        int oid = opponentEntityId;
        PlayerTrack o = oid == 0 ? null : peers.apply(oid);
        if (o == null || o == this) {
            fHasOpp[i] = false;
            return;
        }
        long now = System.nanoTime();
        fHasOpp[i] = true;
        fOppDx[i] = o.xMilli - xMilli;
        fOppDy[i] = o.yMilli - yMilli;
        fOppDz[i] = o.zMilli - zMilli;
        int of = o.pubFlags, ol = o.liveFlags, st = 0;
        if ((of & Window4.F_ON_GROUND) != 0) st |= 1;
        if ((of & Window4.F_SPRINT) != 0) st |= 2;
        if ((of & Window4.F_SNEAK) != 0) st |= 4;
        if ((ol & L_BLOCKING) != 0) st |= 8;
        if ((ol & L_GLIDING) != 0) st |= 16;
        if ((ol & L_USING) != 0) st |= 32;
        fOppState[i] = st;
        fOppBlockAgo[i] = agoMs(o.blockChangeNanos, now);
        fOppHurtAgo[i] = agoMs(o.lastHurtNanos, now);
    }

    private static int agoMs(long then, long now) {
        return then == 0L ? -1 : (int) Math.max(0L, Math.min(65_535L, (now - then) / 1_000_000L));
    }

    // ================================================================ scan thread

    /** Whole frame ring (oldest first) and the events in [fromNanos, toNanos], into the core window and w4. */
    public synchronized void snapshot(Window w, Window4 w4, long fromNanos, long toNanos) {
        int start = (fWrite - fCount + cap) % cap;
        w.n = fCount;
        w4.n = fCount;
        for (int k = 0; k < fCount; k++) {
            int i = (start + k) % cap;
            w.yaw[k] = fYaw[i];
            w.pitch[k] = fPitch[i];
            w.x[k] = fX[i];
            w.z[k] = fZ[i];
            w.flags[k] = fFlags[i];
            w4.tick[k] = fTick[i];
            w4.flags[k] = fFlags[i];
            w4.clientTick[k] = fClientTick[i];
            w4.yMilli[k] = fY[i];
            w4.hasRot[k] = fHasRot[i];
            w4.yawExact[k] = fYawExact[i];
            w4.pitchExact[k] = fPitchExact[i];
            w4.hasOpp[k] = fHasOpp[i];
            w4.oppDx[k] = fOppDx[i];
            w4.oppDy[k] = fOppDy[i];
            w4.oppDz[k] = fOppDz[i];
            w4.oppState[k] = fOppState[i];
            w4.oppBlockAgo[k] = fOppBlockAgo[i];
            w4.oppHurtAgo[k] = fOppHurtAgo[i];
            w4.maxGap[k] = fMaxGap[i];
            w4.burst[k] = fBurst[i];
        }
        w.m = 0;
        w4.m = 0;
        int eStart = (eWrite - eCount + eType.length) % eType.length;
        for (int k = 0; k < eCount; k++) {
            int i = (eStart + k) % eType.length;
            long t = eNanos[i];
            if (t < fromNanos || t > toNanos) {
                continue;
            }
            if (eType[i] == Window4.ATTACK && w.m < w.attacks.length) {
                w.attacks[w.m++] = t;
            }
            if (w4.m < w4.eventCap()) {
                int j = w4.m++;
                w4.eType[j] = eType[i];
                w4.eTick[j] = eTick[i];
                w4.eNanos[j] = t;
                w4.eA[j] = eA[i];
                w4.eB[j] = eB[i];
                w4.eC[j] = eC[i];
                w4.eD[j] = eD[i];
                w4.eG[j] = eG[i];
                w4.eClientTick[j] = eClientTick[i];
            }
        }
    }

    /** Reusable buffers for the 13 core features. */
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
