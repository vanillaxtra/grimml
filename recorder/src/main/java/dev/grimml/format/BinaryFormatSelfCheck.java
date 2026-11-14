package dev.grimml.format;

import dev.grimml.buffer.CombatSnapshot;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

public final class BinaryFormatSelfCheck {

    public static void main(String[] args) throws Exception {
        CombatSnapshot snap = new CombatSnapshot(8, 8);
        snap.fightId = 11;
        snap.windowId = 22;
        snap.role = RecordTypes.ROLE_ATTACKER;
        snap.trigger = RecordTypes.TRIGGER_ATTACK;
        snap.sessionId = 123456789L;
        snap.anonPlayerId = 987654321L;
        snap.windowStartNanos = 1_000_000L;
        snap.windowEndNanos = 2_000_000L;
        snap.windowStartEpochMs = 1_700_000_000_000L;
        snap.bootId = 99;
        snap.stateFlags = RecordTypes.FLAG_SPRINT | RecordTypes.FLAG_BEDROCK;
        snap.pingMs = 42;
        snap.clientProtocol = 767;
        snap.clientBrand = "vanilla";
        snap.serverId = "ffa";
        snap.kit = "mace";
        snap.frameCount = 2;
        snap.fTick[0] = 100;
        snap.fX[0] = 1000;
        snap.fY[0] = 2000;
        snap.fZ[0] = 3000;
        snap.fYaw[0] = 4500;
        snap.fPitch[0] = -1225;
        snap.fTick[1] = 101;
        snap.fX[1] = 1010;
        snap.fY[1] = 2000;
        snap.fZ[1] = 3005;
        snap.fYaw[1] = 4600;
        snap.fPitch[1] = -1200;
        // schema 4 frame extension on frame 0: exact rotation, client tick, opponent
        snap.fExt[0].hasExactRot = true;
        snap.fExt[0].yawExact = 45.0123f;
        snap.fExt[0].pitchExact = -12.25f;
        snap.fExt[0].clientTick = 77;
        snap.fExt[0].oppAnon = 0x1234_5678_9abcL;
        snap.fExt[0].oppDx = -2500;
        snap.fExt[0].oppDy = 0;
        snap.fExt[0].oppDz = 1800;
        snap.fExt[0].oppYaw = 18000;
        snap.fExt[0].oppPose = 1;
        snap.fExt[0].oppState = RecordTypes.OPP_ON_GROUND | RecordTypes.OPP_BLOCKING;
        snap.fExt[0].oppBlockAgo = 120;
        snap.fExt[0].oppHurtAgo = -1;
        snap.fExt[0].nearby = 2;
        snap.fExt[0].maxGapMs = 51;
        snap.fExt[0].burst = 3;
        snap.taxonomyVersion = 1;
        snap.taxonomyHash = 0xabcL;
        snap.eventCount = 2;
        snap.eType[0] = RecordTypes.ATTACK;
        snap.eTick[0] = 101;
        snap.eNanos[0] = 1_160_000L;
        snap.eA[0] = 55;
        snap.eClientTick[0] = 78;
        snap.eG[0] = 1 | (20 << 8);
        snap.eH[0] = 300_000;
        snap.eI[0] = 1_500_000;
        snap.eJ[0] = -250_000;
        snap.eType[1] = RecordTypes.MARKER;
        snap.eTick[1] = 101;
        snap.eNanos[1] = 1_160_100L;
        snap.eA[1] = 1_700_000_000_000L;
        snap.eB[1] = RecordTypes.MARKER_START | (RecordTypes.SOURCE_STAFF << 8) | (1 << 16);
        snap.eC[1] = 2;
        snap.eD[1] = 1;
        snap.eE[1] = 3;
        snap.eF[1] = BinaryFormat.hashCheckName("strength=high");

        byte[] encoded = BinaryFormat.encodeWindow(snap);
        String dump = System.getProperty("grimml.dump");
        if (dump != null) {
            // used to refresh training/tests/fixtures/schema4_sample.gmlml (cross-language decoder test)
            java.nio.file.Files.write(java.nio.file.Path.of(dump), encoded);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[4];
        in.readFully(magic);
        int schema = in.readUnsignedByte();
        long fight = in.readLong();
        long window = in.readLong();
        int role = in.readUnsignedByte();
        in.readUnsignedByte();
        in.readUnsignedByte();
        in.readUnsignedShort();
        long session = in.readLong();
        long anon = in.readLong();
        boolean ok = magic[0] == 'G' && magic[1] == 'M' && magic[2] == 'L' && magic[3] == '4'
                && schema == BinaryFormat.SCHEMA
                && BinaryFormat.schemaOf(encoded) == 4
                && BinaryFormat.schemaOf(new byte[]{'G', 'M', 'L', '2'}) == 2
                && BinaryFormat.schemaOf(new byte[]{'G', 'M', 'L', '1'}) == 1
                && fight == snap.fightId
                && window == snap.windowId
                && role == RecordTypes.ROLE_ATTACKER
                && session == snap.sessionId
                && anon == snap.anonPlayerId
                && encoded.length > 64;
        if (!ok) {
            throw new AssertionError("binary format v2 self-check failed magic/header");
        }
        // skip rest of header until compressed blob
        in.readInt();
        in.readLong();
        in.readLong();
        in.readLong();
        in.readLong();
        in.readInt();
        in.readInt();
        in.readShort();
        in.readShort();
        in.readShort();
        in.readShort();
        in.readShort();
        in.readShort();
        in.readInt();
        in.readByte();
        in.readByte();
        in.readByte();
        in.readInt();
        in.readInt();
        in.readShort();
        in.readShort();
        in.readByte();
        for (int i = 0; i < 8; i++) in.readInt();
        in.readInt();
        in.readInt();
        skipStr(in);
        skipStr(in);
        skipStr(in);
        skipStr(in);
        skipStr(in);
        skipStr(in);
        skipStr(in);
        skipStr(in);
        int taxVer = in.readInt();
        long taxHash = in.readLong();
        if (taxVer != 1 || taxHash != 0xabcL) {
            throw new AssertionError("taxonomy header");
        }
        if (readUVar(in) != 0) {
            throw new AssertionError("header extension should be empty");
        }
        int compressedLen = in.readInt();
        byte[] compressed = new byte[compressedLen];
        in.readFully(compressed);
        DataInputStream rec = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)));
        int frames = rec.readUnsignedShort();
        if (frames != 2) {
            throw new AssertionError("expected 2 frames got " + frames);
        }
        java.util.Map<Integer, DataInputStream> fx = skipFrames(rec, frames);
        DataInputStream rot = fx.get(RecordTypes.FX_ROT_EXACT);
        DataInputStream opp = fx.get(RecordTypes.FX_OPPONENT);
        if (rot == null || rot.readFloat() != 45.0123f || rot.readFloat() != -12.25f) {
            throw new AssertionError("exact rotation round-trip");
        }
        if (BinaryFormat.readVar(fx.get(RecordTypes.FX_CLIENT_TICK)) != 77) {
            throw new AssertionError("client tick round-trip");
        }
        if (opp == null || opp.readLong() != 0x1234_5678_9abcL || BinaryFormat.readVar(opp) != -2500 || BinaryFormat.readVar(opp) != 0
                || BinaryFormat.readVar(opp) != 1800 || BinaryFormat.readVar(opp) != 18000 || opp.readUnsignedByte() != 1
                || opp.readUnsignedByte() != (RecordTypes.OPP_ON_GROUND | RecordTypes.OPP_BLOCKING)
                || BinaryFormat.readVar(opp) != 120 || BinaryFormat.readVar(opp) != -1 || opp.readUnsignedByte() != 2) {
            throw new AssertionError("opponent round-trip");
        }
        DataInputStream pt = fx.get(RecordTypes.FX_PACKET_TIMING);
        if (BinaryFormat.readVar(pt) != 51 || pt.readUnsignedByte() != 3) {
            throw new AssertionError("packet timing round-trip");
        }
        int events = rec.readUnsignedShort();
        if (events != 2) {
            throw new AssertionError("expected 2 events got " + events);
        }
        java.util.Map<Integer, DataInputStream> ex = skipEvent(rec);
        DataInputStream extra = ex.get(RecordTypes.EX_EXTRA);
        if (BinaryFormat.readVar(ex.get(RecordTypes.EX_CLIENT_TICK)) != 78 || extra == null
                || BinaryFormat.readVar(extra) != (1 | (20 << 8)) || BinaryFormat.readVar(extra) != 300_000
                || BinaryFormat.readVar(extra) != 1_500_000 || BinaryFormat.readVar(extra) != -250_000) {
            throw new AssertionError("attack extension round-trip");
        }
        rec.readByte();
        rec.readInt();
        rec.readInt();
        rec.readByte();
        rec.readLong();
        long wall = rec.readLong();
        int packed = rec.readInt();
        int label = rec.readInt();
        int client = rec.readInt();
        int module = rec.readInt();
        int settings = rec.readInt();
        rec.readShort();
        java.util.Map<Integer, DataInputStream> mx = readExt(rec);
        if (mx.containsKey(RecordTypes.EX_EXTRA) || !mx.containsKey(RecordTypes.EX_CLIENT_TICK)) {
            throw new AssertionError("marker extension: client tick only, no extra when all zero");
        }
        if (wall != 1_700_000_000_000L
                || (packed & 0xff) != RecordTypes.MARKER_START
                || label != 2 || client != 1 || module != 3
                || settings != BinaryFormat.hashCheckName("strength=high")) {
            throw new AssertionError("marker round-trip");
        }
        System.out.println("binary format self-check passed: encoded " + encoded.length + " bytes schema " + schema);
    }

    private static void skipStr(DataInputStream in) throws Exception {
        int n = in.readUnsignedByte();
        in.readFully(new byte[n]);
    }

    private static int readUVar(DataInputStream in) throws Exception {
        int v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
        }
    }

    /** Schema 4 extension block -> tag to payload stream. */
    private static java.util.Map<Integer, DataInputStream> readExt(DataInputStream in) throws Exception {
        int len = readUVar(in);
        byte[] block = new byte[len];
        in.readFully(block);
        DataInputStream b = new DataInputStream(new ByteArrayInputStream(block));
        java.util.Map<Integer, DataInputStream> out = new java.util.HashMap<>();
        while (b.available() > 0) {
            int tag = b.readUnsignedByte();
            byte[] payload = new byte[readUVar(b)];
            b.readFully(payload);
            out.put(tag, new DataInputStream(new ByteArrayInputStream(payload)));
        }
        return out;
    }

    // returns frame 0's extension
    private static java.util.Map<Integer, DataInputStream> skipFrames(DataInputStream rec, int frames) throws Exception {
        java.util.Map<Integer, DataInputStream> first = null;
        for (int i = 0; i < frames; i++) {
            rec.readByte();
            rec.readInt();
            rec.readInt();
            rec.readByte();
            rec.readShort();
            if (i == 0) {
                rec.readInt();
                rec.readInt();
                rec.readInt();
                rec.readInt();
                rec.readInt();
            } else {
                BinaryFormat.readVar(rec);
                BinaryFormat.readVar(rec);
                BinaryFormat.readVar(rec);
                BinaryFormat.readVar(rec);
                BinaryFormat.readVar(rec);
            }
            rec.readInt();
            rec.readByte();
            rec.readByte();
            rec.readByte();
            rec.readInt();
            rec.readByte();
            rec.readInt();
            rec.readByte();
            rec.readShort();
            rec.readShort();
            rec.readInt();
            rec.readInt();
            rec.readInt();
            rec.readInt();
            rec.readInt();
            var x = readExt(rec);
            if (i == 0) {
                first = x;
            }
        }
        return first;
    }

    private static java.util.Map<Integer, DataInputStream> skipEvent(DataInputStream rec) throws Exception {
        rec.readByte();
        rec.readInt();
        rec.readInt();
        rec.readByte();
        rec.readLong();
        rec.readLong();
        rec.readInt();
        rec.readInt();
        rec.readInt();
        rec.readInt();
        rec.readInt();
        rec.readShort();
        return readExt(rec);
    }
}
