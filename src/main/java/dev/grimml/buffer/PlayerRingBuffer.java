package dev.grimml.buffer;

import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;
import java.util.concurrent.atomic.AtomicLongArray;

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
    public volatile long lastTeleportNanos;
    public volatile long lastWorldNanos;
    public volatile long lastRespawnNanos;
    public volatile long lastSetbackNanos;
    public volatile byte labelSource;
    public volatile int grimConfidenceCenti;
    public volatile int grimCheckHash;
    // when true only TRIGGER_MARKER activates flush; events still write to ring
    public volatile boolean markOnly;
    // open /grimml mark segment: writer keeps dumping the ring until end
    public volatile boolean markActive;
    public volatile String markId = "";

    public volatile int pendingKbTick = Integer.MIN_VALUE;
    public volatile long totemPopNanos;
    public volatile int clicksSincePop;
    public volatile boolean windowSincePop;
    public volatile long useStartNanos;
    public volatile byte useCat;
    public volatile long lastWindNanos;
    public volatile int horizSpeedMilli;

    private final AtomicLongArray bitNanos = new AtomicLongArray(16);
    private int histTick = Integer.MIN_VALUE;
    private int jumpHist;
    private int groundHist;
    private int sprintHist;
    private int forwardHist;
    private int prevYMilli;

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
            long dx = (long) x - xMilli;
            long dz = (long) z - zMilli;
            horizSpeedMilli = (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt((double) (dx * dx + dz * dz))));
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
        int frameFlags = scratchFlags | liveInputFlags();
        frames.write(
                openTick, lastNsOff, lastOrd, clock.lastDurationMs(),
                xMilli, yMilli, zMilli, yawCenti, pitchCenti,
                frameFlags, pose,
                inv.held(), inv.heldCat(), inv.heldId(), inv.offCat(), inv.offId(),
                packetsThisTick, pingMs, jitterMs, lastAckTx,
                lastGroundYMilli, fallMilli, vehicleType, vehicleId
        );
        pushHistory(openTick, frameFlags);
        checkJumpReset(openTick);
        checkRetotemTimeout();
        packetsThisTick = 0;
        scratchFlags &= ~(FrameRing.F_CLIENT_TICK_END | FrameRing.F_JUMP_EDGE);
        openTick = Integer.MIN_VALUE;
    }

    public void writeEvent(byte type, int tick, int nsOff, int ord, long nanos, long a, int b, int c, int d, int e, int f) {
        events.write(type, tick, nsOff, ord, nanos, a, b, c, d, e, f, pingMs);
    }

    // bit i of each history int is the frame i ticks before histTick
    private void pushHistory(int tick, int flags) {
        int shift = histTick == Integer.MIN_VALUE ? 1 : tick - histTick;
        if (shift <= 0) {
            return;
        }
        histTick = tick;
        boolean ground = (flags & FrameRing.F_ON_GROUND) != 0;
        boolean wasGround = (groundHist & 1) != 0;
        boolean jumped = (flags & FrameRing.F_JUMP_EDGE) != 0 || (wasGround && !ground && yMilli > prevYMilli);
        prevYMilli = yMilli;
        jumpHist = push(jumpHist, shift, jumped);
        groundHist = push(groundHist, shift, ground);
        sprintHist = push(sprintHist, shift, (flags & FrameRing.F_SPRINT) != 0);
        forwardHist = push(forwardHist, shift, (flags & FrameRing.F_INPUT_W) != 0);
    }

    private static int push(int hist, int shift, boolean bit) {
        int h = shift >= 32 ? 0 : hist << shift;
        return bit ? h | 1 : h;
    }

    private void checkJumpReset(int tick) {
        int kb = pendingKbTick;
        if (kb == Integer.MIN_VALUE || tick < kb + RecordTypes.JUMP_RESET_AFTER) {
            return;
        }
        pendingKbTick = Integer.MIN_VALUE;
        int span = RecordTypes.JUMP_RESET_BEFORE + RecordTypes.JUMP_RESET_AFTER + 1;
        int jump = 0;
        int ground = 0;
        int sprint = 0;
        int forward = 0;
        int first = RecordTypes.JUMP_RESET_NONE;
        for (int i = 0; i < span; i++) {
            int k = histTick - (kb - RecordTypes.JUMP_RESET_BEFORE + i);
            if (k < 0 || k >= 32) {
                continue;
            }
            if (((jumpHist >>> k) & 1) != 0) {
                jump |= 1 << i;
                if (first == RecordTypes.JUMP_RESET_NONE) {
                    first = i - RecordTypes.JUMP_RESET_BEFORE;
                }
            }
            ground |= ((groundHist >>> k) & 1) << i;
            sprint |= ((sprintHist >>> k) & 1) << i;
            forward |= ((forwardHist >>> k) & 1) << i;
        }
        long nanos = System.nanoTime();
        events.write(RecordTypes.JUMP_RESET, tick, clock.nsOffset(nanos), 0, nanos, kb, first, jump, ground, sprint, forward, pingMs);
    }

    private void checkRetotemTimeout() {
        long pop = totemPopNanos;
        if (pop == 0L) {
            return;
        }
        long nanos = System.nanoTime();
        if (nanos - pop > 5_000_000_000L) {
            emitRetotem(RecordTypes.RETOTEM_FAILED, nanos);
        }
    }

    public void emitRetotem(int method, long nanos) {
        long pop = totemPopNanos;
        if (pop == 0L) {
            return;
        }
        totemPopNanos = 0L;
        int clicks = clicksSincePop;
        int failed = method == RecordTypes.RETOTEM_CLICK ? Math.max(0, clicks - 1) : clicks;
        events.write(RecordTypes.RETOTEM, clock.tick(), clock.nsOffset(nanos), 0, nanos,
                nanos - pop, method, clicks, failed, windowSincePop ? 1 : 0, inv.hotbarHasTotem() ? 1 : 0, pingMs);
        clicksSincePop = 0;
        windowSincePop = false;
    }

    public void totemPopped(long nanos) {
        if (totemPopNanos != 0L) {
            emitRetotem(RecordTypes.RETOTEM_FAILED, nanos);
        }
        if (inv.offCat() == ItemCategories.TOTEM) {
            inv.setSlot(InventoryMirror.OFFHAND, 0, ItemCategories.FIST, 0);
        } else if (inv.heldCat() == ItemCategories.TOTEM) {
            inv.setSlot(InventoryMirror.HOTBAR0 + inv.held(), 0, ItemCategories.FIST, 0);
        }
        totemPopNanos = nanos;
        clicksSincePop = 0;
        windowSincePop = false;
    }

    public int validityBetween(long from, long to) {
        int v = 0;
        for (int bit = 0; bit < 16; bit++) {
            long t = bitNanos.get(bit);
            if (t != 0L && t >= from && t <= to) {
                v |= 1 << bit;
            }
        }
        return v;
    }

    public void markCombat(long nowNanos, long postNanos, byte trigger) {
        if (markOnly && trigger != RecordTypes.TRIGGER_MARKER) {
            lastAttackNanos = nowNanos;
            return;
        }
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
        long now = System.nanoTime();
        for (int b = 0; b < 16; b++) {
            if ((bit & (1 << b)) != 0) {
                bitNanos.set(b, now);
            }
        }
        if ((bit & RecordTypes.VALID_TELEPORT) != 0) {
            lastTeleportNanos = now;
        }
        if ((bit & RecordTypes.VALID_WORLD) != 0) {
            lastWorldNanos = now;
        }
        if ((bit & RecordTypes.VALID_RESPAWN) != 0) {
            lastRespawnNanos = now;
        }
        if ((bit & RecordTypes.VALID_SETBACK) != 0) {
            lastSetbackNanos = now;
        }
    }

    public void stampGrimWeak(int checkHash, int confidenceCenti) {
        if (RecordTypes.protectedLabel(labelSource)) {
            return;
        }
        labelSource = RecordTypes.LABEL_GRIM_WEAK;
        grimCheckHash = checkHash;
        grimConfidenceCenti = confidenceCenti;
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
