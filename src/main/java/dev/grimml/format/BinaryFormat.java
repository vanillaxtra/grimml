package dev.grimml.format;

import dev.grimml.buffer.CombatSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;


public final class BinaryFormat {

    public static final int SCHEMA = 1;
    public static final byte[] MAGIC = {'G', 'M', 'L', '1'};

    private BinaryFormat() {}

    public static byte[] encodeWindow(CombatSnapshot snap) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(256 + snap.rotCount * 20 + snap.eventCount * 24);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.write(MAGIC);
            out.writeByte(SCHEMA);
            out.writeLong(snap.sessionId);
            out.writeLong(snap.anonPlayerId);
            out.writeLong(snap.windowStartNanos);
            out.writeLong(snap.windowEndNanos);
            out.writeShort(snap.stateFlags & 0xFFFF);
            out.writeShort(snap.pingMs & 0xFFFF);
            out.writeShort(snap.clientProtocol & 0xFFFF);
            byte[] brand = snap.clientBrand == null
                    ? new byte[0]
                    : snap.clientBrand.getBytes(StandardCharsets.UTF_8);
            if (brand.length > 512) {
                byte[] trimmed = new byte[512];
                System.arraycopy(brand, 0, trimmed, 0, 512);
                brand = trimmed;
            }
            out.writeShort(brand.length);
            out.write(brand);

            ByteArrayOutputStream records = new ByteArrayOutputStream(snap.rotCount * 20 + snap.eventCount * 24);
            DataOutputStream rec = new DataOutputStream(records);
            for (int i = 0; i < snap.rotCount; i++) {
                rec.writeByte(RecordTypes.ROTATION);
                rec.writeInt(snap.rotTick[i]);
                rec.writeFloat(snap.yaw[i]);
                rec.writeFloat(snap.pitch[i]);
                rec.writeLong(snap.rotNanos[i]);
            }
            for (int i = 0; i < snap.eventCount; i++) {
                byte type = snap.eventType[i];
                rec.writeByte(type);
                rec.writeInt(snap.eventTick[i]);
                rec.writeLong(snap.eventNanos[i]);
                switch (type) {
                    case RecordTypes.ATTACK -> {
                        rec.writeInt(snap.eventA[i]);
                        rec.writeInt(snap.eventB[i]); // hitOff packed or 0
                        rec.writeShort(snap.eventPing[i]);
                    }
                    case RecordTypes.STATE -> {
                        rec.writeShort(snap.eventA[i] & 0xFFFF);
                        rec.writeShort(snap.eventPing[i]);
                    }
                    case RecordTypes.GRIM_FLAG -> {
                        rec.writeInt(snap.eventA[i]); // check name hash
                        rec.writeShort(snap.eventPing[i]);
                    }
                    default -> {
                        // skip unknown - still wrote type/tick/nanos; pad nothing
                    }
                }
            }
            rec.flush();

            byte[] plain = records.toByteArray();
            ByteArrayOutputStream zipped = new ByteArrayOutputStream(plain.length / 2 + 16);
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(zipped, def)) {
                dos.write(plain);
            } finally {
                def.end();
            }
            byte[] compressed = zipped.toByteArray();
            out.writeInt(compressed.length);
            out.write(compressed);
        }
        return raw.toByteArray();
    }

    public static int hashCheckName(String name) {
        if (name == null) {
            return 0;
        }
        // FNV-1a 32-bit - stable across JVMs, no alloc beyond the string we already have
        int h = 0x811c9dc5;
        for (int i = 0; i < name.length(); i++) {
            h ^= name.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
