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
        snap.taxonomyVersion = 1;
        snap.taxonomyHash = 0xabcL;
        snap.eventCount = 2;
        snap.eType[0] = RecordTypes.ATTACK;
        snap.eTick[0] = 101;
        snap.eNanos[0] = 1_160_000L;
        snap.eA[0] = 55;
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
        boolean ok = magic[0] == 'G' && magic[1] == 'M' && magic[2] == 'L' && magic[3] == '3'
                && schema == BinaryFormat.SCHEMA
                && BinaryFormat.schemaOf(encoded) == 3
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
        int compressedLen = in.readInt();
        byte[] compressed = new byte[compressedLen];
        in.readFully(compressed);
        DataInputStream rec = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)));
        int frames = rec.readUnsignedShort();
        if (frames != 2) {
            throw new AssertionError("expected 2 frames got " + frames);
        }
        skipFrames(rec, frames);
        int events = rec.readUnsignedShort();
        if (events != 2) {
            throw new AssertionError("expected 2 events got " + events);
        }
        skipEvent(rec);
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

    private static void skipFrames(DataInputStream rec, int frames) throws Exception {
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
        }
    }

    private static void skipEvent(DataInputStream rec) throws Exception {
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
    }
}
