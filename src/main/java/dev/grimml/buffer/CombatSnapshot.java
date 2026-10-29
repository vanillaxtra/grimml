package dev.grimml.buffer;

public final class CombatSnapshot {

    public long fightId;
    public long windowId;
    public byte role;
    public byte trigger;
    public byte labelSource;
    public int validity;
    public long sessionId;
    public long anonPlayerId;
    public int entityId;
    public int stateFlags;
    public int pingMs;
    public int jitterMs;
    public String clientBrand = "";
    public int clientProtocol;
    public long windowStartNanos;
    public long windowEndNanos;
    public long windowStartEpochMs;
    public long bootId;
    public int tickStart;
    public int tickEnd;
    public int msptStartCenti;
    public int msptEndCenti;
    public int tpsCenti;
    public boolean testAccount;
    public byte mainHand;
    public int gamemode;
    public int healthMilli;
    public int absorptionMilli;
    public int food;
    public int totemCount;
    public boolean hotbarTotem;
    public int attrDamage, attrSpeed, attrRange, attrMove, attrJump, attrScale, attrKbRes, attrGravity;
    public int effectsPacked0, effectsPacked1;
    public String serverId = "";
    public String kit = "";
    public String world = "";
    public String mapId = "";
    public String mcVersion = "";
    public String grimVersion = "";
    public String peVersion = "";
    public boolean relativePositions;

    public int frameCount;
    public final int[] fTick, fNsOff, fFlags, fHeldId, fOffId, fAck, fGroundY, fFall, fVehType, fVehId;
    public final int[] fX, fY, fZ, fYaw, fPitch;
    public final short[] fDur, fPing, fJitter;
    public final byte[] fOrder, fPose, fSlot, fHeldCat, fOffCat, fPackets;

    public int eventCount;
    public final byte[] eType, eOrder;
    public final int[] eTick, eNsOff, eB, eC, eD, eE, eF;
    public final long[] eNanos, eA;
    public final short[] ePing;

    private final FrameRing.FrameOut frameTmp = new FrameRing.FrameOut();
    private final EventRing.EventOut eventTmp = new EventRing.EventOut();

    public CombatSnapshot(int frameCap, int eventCap) {
        fTick = new int[frameCap];
        fNsOff = new int[frameCap];
        fFlags = new int[frameCap];
        fHeldId = new int[frameCap];
        fOffId = new int[frameCap];
        fAck = new int[frameCap];
        fGroundY = new int[frameCap];
        fFall = new int[frameCap];
        fVehType = new int[frameCap];
        fVehId = new int[frameCap];
        fX = new int[frameCap];
        fY = new int[frameCap];
        fZ = new int[frameCap];
        fYaw = new int[frameCap];
        fPitch = new int[frameCap];
        fDur = new short[frameCap];
        fPing = new short[frameCap];
        fJitter = new short[frameCap];
        fOrder = new byte[frameCap];
        fPose = new byte[frameCap];
        fSlot = new byte[frameCap];
        fHeldCat = new byte[frameCap];
        fOffCat = new byte[frameCap];
        fPackets = new byte[frameCap];
        eType = new byte[eventCap];
        eOrder = new byte[eventCap];
        eTick = new int[eventCap];
        eNsOff = new int[eventCap];
        eB = new int[eventCap];
        eC = new int[eventCap];
        eD = new int[eventCap];
        eE = new int[eventCap];
        eF = new int[eventCap];
        eNanos = new long[eventCap];
        eA = new long[eventCap];
        ePing = new short[eventCap];
    }

    public void reset() {
        frameCount = 0;
        eventCount = 0;
        clientBrand = "";
        fightId = 0;
        windowId = 0;
        labelSource = 0;
        relativePositions = false;
    }

    public boolean copyFrom(PlayerRingBuffer buf, long windowStart, long windowEnd) {
        FrameRing fr = buf.frames;
        int rc = fr.count();
        int rw = fr.writeIndex();
        int start = (rw - rc + fr.cap) % fr.cap;
        int torn = 0;
        for (int n = 0; n < rc; n++) {
            int i = (start + n) % fr.cap;
            if (!fr.copySlot(i, frameTmp)) {
                torn++;
                continue;
            }
            if (frameCount >= fTick.length) {
                break;
            }
            int k = frameCount++;
            fTick[k] = frameTmp.tick;
            fNsOff[k] = frameTmp.nsOffset;
            fOrder[k] = frameTmp.order;
            fDur[k] = frameTmp.tickDur;
            fX[k] = frameTmp.xMilli;
            fY[k] = frameTmp.yMilli;
            fZ[k] = frameTmp.zMilli;
            fYaw[k] = frameTmp.yawCenti;
            fPitch[k] = frameTmp.pitchCenti;
            fFlags[k] = frameTmp.flags;
            fPose[k] = frameTmp.pose;
            fSlot[k] = frameTmp.heldSlot;
            fHeldCat[k] = frameTmp.heldCat;
            fHeldId[k] = frameTmp.heldId;
            fOffCat[k] = frameTmp.offCat;
            fOffId[k] = frameTmp.offId;
            fPackets[k] = frameTmp.packets;
            fPing[k] = frameTmp.ping;
            fJitter[k] = frameTmp.jitter;
            fAck[k] = frameTmp.ackTx;
            fGroundY[k] = frameTmp.lastGroundY;
            fFall[k] = frameTmp.fallMilli;
            fVehType[k] = frameTmp.vehicleType;
            fVehId[k] = frameTmp.vehicleId;
        }
        EventRing er = buf.events;
        int ec = er.count();
        int ew = er.writeIndex();
        int eStart = (ew - ec + er.cap) % er.cap;
        for (int n = 0; n < ec; n++) {
            int i = (eStart + n) % er.cap;
            if (!er.copySlot(i, eventTmp)) {
                torn++;
                continue;
            }
            if (eventTmp.nanos < windowStart || eventTmp.nanos > windowEnd) {
                continue;
            }
            if (eventCount >= eType.length) {
                break;
            }
            int k = eventCount++;
            eType[k] = eventTmp.type;
            eTick[k] = eventTmp.tick;
            eNsOff[k] = eventTmp.nsOffset;
            eOrder[k] = eventTmp.order;
            eNanos[k] = eventTmp.nanos;
            eA[k] = eventTmp.a;
            eB[k] = eventTmp.b;
            eC[k] = eventTmp.c;
            eD[k] = eventTmp.d;
            eE[k] = eventTmp.e;
            eF[k] = eventTmp.f;
            ePing[k] = eventTmp.ping;
        }
        if (torn > 0) {
            validity |= dev.grimml.format.RecordTypes.VALID_PARTIAL;
        }
        return frameCount > 0 || eventCount > 0;
    }
}
