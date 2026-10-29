package dev.grimml.format;

import dev.grimml.buffer.CombatSnapshot;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

public final class BinaryFormatSelfCheck {

    public static void main(String[] args) throws Exception {
        CombatSnapshot snap = new CombatSnapshot(8, 8);
        snap.sessionId = 123456789L;
        snap.anonPlayerId = 987654321L;
        snap.windowStartNanos = 1_000_000L;
        snap.windowEndNanos = 2_000_000L;
        snap.stateFlags = RecordTypes.FLAG_SPRINT | RecordTypes.FLAG_BEDROCK;
        snap.pingMs = 42;
        snap.clientProtocol = 767;
        snap.clientBrand = "vanilla";

        snap.addRotation(100, 45.5f, -12.25f, 1_100_000L);
        snap.addRotation(101, 46.0f, -12.0f, 1_150_000L);
        snap.addEvent(RecordTypes.ATTACK, 101, 1_160_000L, 12345, 0, 0, 42);
        snap.addEvent(RecordTypes.STATE, 100, 1_100_000L, snap.stateFlags, 0, 0, 42);

        byte[] encoded = BinaryFormat.encodeWindow(snap);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[4];
        in.readFully(magic);
        int schema = in.readUnsignedByte();
        long session = in.readLong();
        long anon = in.readLong();
        long start = in.readLong();
        long end = in.readLong();
        int flags = in.readUnsignedShort();
        int ping = in.readUnsignedShort();
        int proto = in.readUnsignedShort();
        int brandLen = in.readUnsignedShort();
        byte[] brandBytes = new byte[brandLen];
        in.readFully(brandBytes);
        String brand = new String(brandBytes, StandardCharsets.UTF_8);
        int compressedLen = in.readInt();
        byte[] compressed = new byte[compressedLen];
        in.readFully(compressed);

        DataInputStream rec = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)));
        int rotations = 0;
        int events = 0;
        while (rec.available() > 0) {
            byte type = rec.readByte();
            if (type == RecordTypes.ROTATION) {
                rec.readInt();
                rec.readFloat();
                rec.readFloat();
                rec.readLong();
                rotations++;
            } else if (type == RecordTypes.ATTACK) {
                rec.readInt();
                rec.readLong();
                rec.readInt();
                rec.readInt();
                rec.readShort();
                events++;
            } else if (type == RecordTypes.STATE) {
                rec.readInt();
                rec.readLong();
                rec.readShort();
                rec.readShort();
                events++;
            } else if (type == RecordTypes.GRIM_FLAG) {
                rec.readInt();
                rec.readLong();
                rec.readInt();
                rec.readShort();
                events++;
            }
        }

        boolean ok = magic[0] == 'G' && magic[1] == 'M' && magic[2] == 'L' && magic[3] == '1'
                && schema == BinaryFormat.SCHEMA
                && session == snap.sessionId
                && anon == snap.anonPlayerId
                && flags == snap.stateFlags
                && ping == snap.pingMs
                && proto == snap.clientProtocol
                && brand.equals(snap.clientBrand)
                && rotations == snap.rotCount
                && events == snap.eventCount;

        if (!ok) {
            throw new AssertionError("binary format self-check failed");
        }
        System.out.println("binary format self-check passed: encoded " + encoded.length + " bytes, " + rotations + " rotations, " + events + " events");
    }
}
