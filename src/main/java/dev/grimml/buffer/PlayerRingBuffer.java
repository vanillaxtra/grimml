package dev.grimml.buffer;

import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;

public final class PlayerRingBuffer {

    public final long sessionId;
    public final long anonPlayerId;
    public final int entityId;
    public final FrameRing frames;
    public final EventRing events;
    public final InventoryMirror inv = new InventoryMirror();

    public volatile int stateFlags;
    public volatile int pingMs;
    public volatile int jitterMs;
    public volatile int lastAckTx;
    public volatile long lastAttackNanos;
    public volatile long combatActiveUntilNanos;
    public volatile long fightId;
    public volatile byte triggerReason;
    public volatile int validity;
    public volatile int lastTargetEntityId;
    public volatile long lastTargetAnonId;

    public volatile String clientBrand = "";
    public volatile int clientProtocol;
    public volatile boolean bedrock;
    public volatile boolean testAccount;
    public volatile boolean staff;
    public volatile byte mainHand; // 0 right 1 left
    public volatile int gamemode;

    public volatile int healthMilli;
    public volatile int absorptionMilli;
    public volatile int food;
    public volatile int pose;
    public volatile int liveFlags;
    public volatile int lastGroundYMilli;
    public volatile int fallMilli;
    public volatile int vehicleType;
    public volatile int vehicleId;
    public volatile int lastRttMs;

    public volatile int xMilli;
    public volatile int yMilli;
    public volatile int zMilli;
    public volatile int yawCenti;
    public volatile int pitchCenti;

    public volatile int attrDamage;
    public volatile int attrSpeed;
    public volatile int attrRange;
    public volatile int attrMove;
    public volatile int attrJump;
    public volatile int attrScale;
    public volatile int attrKbRes;
    public volatile int attrGravity;
    public volatile int effectsPacked0;
    public volatile int effectsPacked1;

    public volatile long sessionStartEpoch;
    public volatile long sessionStartNanos;

    private int orderInTick;
    private int orderTick = Integer.MIN_VALUE;
    private int packetsThisTick;
    private int openTick = Integer.MIN_VALUE;
    private int scratchFlags;
    private int lastNsOff;
    private int lastOrd;
    private boolean jumpWasOn;

    private final TickClock clock;

    public PlayerRingBuffer(long sessionId, long anonPlayerId, int entityId, int ringCap, int eventCap, TickClock clock) {
        this.sessionId = sessionId;
        this.anonPlayerId = anonPlayerId;
        this.entityId = entityId;
        this.clock = clock;
        this.frames = new FrameRing(ringCap);
        this.events = new EventRing(eventCap);
        this.sessionStartEpoch = System.currentTimeMillis();
        this.sessionStartNanos = System.nanoTime();
    }

    public int nextOrder(int tick) {
        if (orderTick != tick) {
            if (openTick != Integer.MIN_VALUE && openTick != tick) {
                commitFrame();
            }
            orderTick = tick;
            orderInTick = 0;
            packetsThisTick = 0;
            openTick = tick;
        }
        packetsThisTick++;
        return ++orderInTick;
    }

    public void applyFlying(int tick, int nsOff, int ord, int x, int y, int z, int yaw, int pitch, boolean onGround, boolean horiz, boolean hasPos, boolean hasRot) {
        if (hasPos) {
            xMilli = x;
            yMilli = y;
            zMilli = z;
            if (onGround) {
                lastGroundYMilli = y;
                fallMilli = 0;
            } else if (lastGroundYMilli != 0) {
                int fall = lastGroundYMilli - y;
                fallMilli = Math.max(0, fall);
            }
        }
        if (hasRot) {
            yawCenti = yaw;
            pitchCenti = pitch;
        }
        if (onGround) {
            scratchFlags |= FrameRing.F_ON_GROUND;
        } else {
            scratchFlags &= ~FrameRing.F_ON_GROUND;
        }
        if (horiz) {
            scratchFlags |= FrameRing.F_HORIZ_COLL;
        } else {
            scratchFlags &= ~FrameRing.F_HORIZ_COLL;
        }
        lastNsOff = nsOff;
        lastOrd = ord;
        if (openTick != Integer.MIN_VALUE && openTick != tick) {
            flushOpenFrame();
        }
        openTick = tick;
    }

    public void applyInput(boolean w, boolean a, boolean s, boolean d, boolean jump, boolean sneak, boolean sprint) {
        int f = scratchFlags;
        f &= ~(FrameRing.F_INPUT_W | FrameRing.F_INPUT_A | FrameRing.F_INPUT_S | FrameRing.F_INPUT_D
                | FrameRing.F_JUMP | FrameRing.F_SNEAK | FrameRing.F_SPRINT | FrameRing.F_INPUT_INFERRED | FrameRing.F_JUMP_EDGE);
        if (w) f |= FrameRing.F_INPUT_W;
        if (a) f |= FrameRing.F_INPUT_A;
        if (s) f |= FrameRing.F_INPUT_S;
        if (d) f |= FrameRing.F_INPUT_D;
        if (jump) f |= FrameRing.F_JUMP;
        if (sneak) f |= FrameRing.F_SNEAK;
        if (sprint) f |= FrameRing.F_SPRINT;
        if (jump && !jumpWasOn) {
            f |= FrameRing.F_JUMP_EDGE;
        }
        jumpWasOn = jump;
        scratchFlags = f;
        if (sneak) {
            liveFlags |= RecordTypes.FLAG_SNEAK;
        } else {
            liveFlags &= ~RecordTypes.FLAG_SNEAK;
        }
        if (sprint) {
            liveFlags |= RecordTypes.FLAG_SPRINT;
        } else {
            liveFlags &= ~RecordTypes.FLAG_SPRINT;
        }
    }

    public void markClientTickEnd() {
        scratchFlags |= FrameRing.F_CLIENT_TICK_END;
        flushOpenFrame();
    }

    public void commitFrame() {
        flushOpenFrame();
    }

    private void flushOpenFrame() {
        if (openTick == Integer.MIN_VALUE) {
            return;
        }
        frames.write(
                openTick, lastNsOff, lastOrd, clock.lastDurationMs(),
                xMilli, yMilli, zMilli, yawCenti, pitchCenti,
                scratchFlags | liveInputFlags(), pose,
                inv.held(), inv.heldCat(), inv.heldId(), inv.offCat(), inv.offId(),
                packetsThisTick, pingMs, jitterMs, lastAckTx,
                lastGroundYMilli, fallMilli, vehicleType, vehicleId
        );
        if (frames.overflows() > 0) {
            validity |= RecordTypes.VALID_PARTIAL;
        }
        packetsThisTick = 0;
        scratchFlags &= ~(FrameRing.F_CLIENT_TICK_END | FrameRing.F_JUMP_EDGE);
        openTick = Integer.MIN_VALUE;
    }

    public void writeEvent(byte type, int tick, int nsOff, int ord, long nanos, long a, int b, int c, int d, int e, int f) {
        events.write(type, tick, nsOff, ord, nanos, a, b, c, d, e, f, pingMs);
        if (events.overflows() > 0) {
            validity |= RecordTypes.VALID_PARTIAL;
        }
    }

    public void markCombat(long nowNanos, long postNanos, byte trigger) {
        lastAttackNanos = nowNanos;
        long until = nowNanos + postNanos;
        if (until > combatActiveUntilNanos) {
            combatActiveUntilNanos = until;
        }
        triggerReason = trigger;
    }

    public boolean isCombatActive(long nowNanos) {
        return nowNanos <= combatActiveUntilNanos;
    }

    public void orValidity(int bit) {
        validity |= bit;
    }

    public boolean jumpHeld() {
        return (scratchFlags & FrameRing.F_JUMP) != 0;
    }

    public long distSqTo(PlayerRingBuffer other) {
        long dx = (long) xMilli - other.xMilli;
        long dy = (long) yMilli - other.yMilli;
        long dz = (long) zMilli - other.zMilli;
        return dx * dx + dy * dy + dz * dz;
    }

    private int liveInputFlags() {
        int f = scratchFlags;
        if ((liveFlags & RecordTypes.FLAG_SPRINT) != 0) f |= FrameRing.F_SPRINT;
        if ((liveFlags & RecordTypes.FLAG_SNEAK) != 0) f |= FrameRing.F_SNEAK;
        if ((liveFlags & RecordTypes.FLAG_BLOCKING) != 0) {
            // blocking is in liveFlags via RecordTypes not frame bits
        }
        return f;
    }
}
