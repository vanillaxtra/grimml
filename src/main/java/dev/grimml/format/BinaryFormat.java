package dev.grimml.format;

import dev.grimml.buffer.CombatSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class BinaryFormat {

    public static final int SCHEMA = 3;
    public static final byte[] MAGIC = {'G', 'M', 'L', '3'};
    public static final byte[] MAGIC_V2 = {'G', 'M', 'L', '2'};
    public static final byte[] MAGIC_V1 = {'G', 'M', 'L', '1'};

    private BinaryFormat() {}

    public static byte[] encodeWindow(CombatSnapshot snap) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(512 + snap.frameCount * 48 + snap.eventCount * 40);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.write(MAGIC);
            out.writeByte(SCHEMA);
            out.writeLong(snap.fightId);
            out.writeLong(snap.windowId);
            out.writeByte(snap.role);
            out.writeByte(snap.trigger);
            out.writeByte(snap.labelSource);
            out.writeShort(snap.validity);
            out.writeLong(snap.sessionId);
            out.writeLong(snap.anonPlayerId);
            out.writeInt(snap.entityId);
            out.writeLong(snap.windowStartEpochMs);
            out.writeLong(snap.windowStartNanos);
            out.writeLong(snap.windowEndNanos);
            out.writeLong(snap.bootId);
            out.writeInt(snap.tickStart);
            out.writeInt(snap.tickEnd);
            out.writeShort(snap.msptStartCenti);
            out.writeShort(snap.msptEndCenti);
            out.writeShort(snap.tpsCenti);
            out.writeShort(snap.pingMs);
            out.writeShort(snap.jitterMs);
            out.writeShort(snap.clientProtocol);
            out.writeInt(snap.stateFlags);
            out.writeByte(snap.mainHand);
            out.writeByte(snap.gamemode);
            out.writeByte(snap.testAccount ? 1 : 0);
            out.writeInt(snap.healthMilli);
            out.writeInt(snap.absorptionMilli);
            out.writeShort(snap.food);
            out.writeShort(snap.totemCount);
            out.writeByte(snap.hotbarTotem ? 1 : 0);
            out.writeInt(snap.attrDamage);
            out.writeInt(snap.attrSpeed);
            out.writeInt(snap.attrRange);
            out.writeInt(snap.attrMove);
            out.writeInt(snap.attrJump);
            out.writeInt(snap.attrScale);
            out.writeInt(snap.attrKbRes);
            out.writeInt(snap.attrGravity);
            out.writeInt(snap.effectsPacked0);
            out.writeInt(snap.effectsPacked1);
            writeStr(out, snap.clientBrand);
            writeStr(out, snap.serverId);
            writeStr(out, snap.kit);
            writeStr(out, snap.world);
            writeStr(out, snap.mapId);
            writeStr(out, snap.mcVersion);
            writeStr(out, snap.grimVersion);
            writeStr(out, snap.peVersion);
            out.writeInt(snap.taxonomyVersion);
            out.writeLong(snap.taxonomyHash);

            ByteArrayOutputStream records = new ByteArrayOutputStream(snap.frameCount * 40 + snap.eventCount * 32);
            DataOutputStream rec = new DataOutputStream(records);
            rec.writeShort(snap.frameCount);
            int ox = 0, oy = 0, oz = 0, oyaw = 0, opitch = 0;
            int originX = snap.frameCount > 0 ? snap.fX[0] : 0;
            int originY = snap.frameCount > 0 ? snap.fY[0] : 0;
            int originZ = snap.frameCount > 0 ? snap.fZ[0] : 0;
            for (int i = 0; i < snap.frameCount; i++) {
                rec.writeByte(RecordTypes.FRAME);
                rec.writeInt(snap.fTick[i]);
                rec.writeInt(snap.fNsOff[i]);
                rec.writeByte(snap.fOrder[i]);
                rec.writeShort(snap.fDur[i]);
                int x = snap.fX[i];
                int y = snap.fY[i];
                int z = snap.fZ[i];
                if (snap.relativePositions) {
                    x -= originX;
                    y -= originY;
                    z -= originZ;
                }
                if (i == 0) {
                    rec.writeInt(x);
                    rec.writeInt(y);
                    rec.writeInt(z);
                    rec.writeInt(snap.fYaw[i]);
                    rec.writeInt(snap.fPitch[i]);
                } else {
                    writeVar(rec, x - ox);
                    writeVar(rec, y - oy);
                    writeVar(rec, z - oz);
                    writeVar(rec, snap.fYaw[i] - oyaw);
                    writeVar(rec, snap.fPitch[i] - opitch);
                }
                ox = x;
                oy = y;
                oz = z;
                oyaw = snap.fYaw[i];
                opitch = snap.fPitch[i];
                rec.writeInt(snap.fFlags[i]);
                rec.writeByte(snap.fPose[i]);
                rec.writeByte(snap.fSlot[i]);
                rec.writeByte(snap.fHeldCat[i]);
                rec.writeInt(snap.fHeldId[i]);
                rec.writeByte(snap.fOffCat[i]);
                rec.writeInt(snap.fOffId[i]);
                rec.writeByte(snap.fPackets[i]);
                rec.writeShort(snap.fPing[i]);
                rec.writeShort(snap.fJitter[i]);
                rec.writeInt(snap.fAck[i]);
                rec.writeInt(snap.fGroundY[i]);
                rec.writeInt(snap.fFall[i]);
                rec.writeInt(snap.fVehType[i]);
                rec.writeInt(snap.fVehId[i]);
            }
            rec.writeShort(snap.eventCount);
            for (int i = 0; i < snap.eventCount; i++) {
                rec.writeByte(snap.eType[i]);
                rec.writeInt(snap.eTick[i]);
                rec.writeInt(snap.eNsOff[i]);
                rec.writeByte(snap.eOrder[i]);
                rec.writeLong(snap.eNanos[i]);
                rec.writeLong(snap.eA[i]);
                rec.writeInt(snap.eB[i]);
                rec.writeInt(snap.eC[i]);
                rec.writeInt(snap.eD[i]);
                rec.writeInt(snap.eE[i]);
                rec.writeInt(snap.eF[i]);
                rec.writeShort(snap.ePing[i]);
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

    public static int schemaOf(byte[] data) {
        if (data == null || data.length < 4) {
            return 0;
        }
        if (data[0] != 'G' || data[1] != 'M' || data[2] != 'L') {
            return 0;
        }
        return data[3] - '0';
    }

    public static int hashCheckName(String name) {
        if (name == null) {
            return 0;
        }
        int h = 0x811c9dc5;
        for (int i = 0; i < name.length(); i++) {
            h ^= name.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            byte[] t = new byte[255];
            System.arraycopy(b, 0, t, 0, 255);
            b = t;
        }
        out.writeByte(b.length);
        out.write(b);
    }

    static void writeVar(DataOutputStream out, int v) throws IOException {
        int u = (v << 1) ^ (v >> 31);
        while ((u & ~0x7F) != 0) {
            out.writeByte((u & 0x7F) | 0x80);
            u >>>= 7;
        }
        out.writeByte(u);
    }

    static int readVar(java.io.DataInputStream in) throws IOException {
        int u = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            u |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return (u >>> 1) ^ -(u & 1);
    }
}
