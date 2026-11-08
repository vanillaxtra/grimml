package dev.grimml.format;

import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.inference.FeatureExtractor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.InflaterInputStream;

// full GML3 round trip: every header field, every frame field, one event of every record type
// with an output dir arg it also writes all-types.gmlml + all-types.json for the python decoder and feature parity tests
public final class BinaryFormatSelfCheck {

    public static void main(String[] args) throws Exception {
        CombatSnapshot snap = sample();
        byte[] encoded = BinaryFormat.encodeWindow(snap);
        verify(snap, encoded);
        if (BinaryFormat.schemaOf(new byte[]{'G', 'M', 'L', '2'}) != 2 || BinaryFormat.schemaOf(new byte[]{'G', 'M', 'L', '1'}) != 1) {
            throw new AssertionError("schemaOf legacy magic");
        }
        if (args.length > 0) {
            Path dir = Path.of(args[0]);
            Files.createDirectories(dir);
            Files.write(dir.resolve("all-types.gmlml"), encoded);
            Files.writeString(dir.resolve("all-types.json"), json(snap, FeatureExtractor.extract(snap)), StandardCharsets.UTF_8);
        }
        System.out.println("binary format self-check passed: " + snap.eventCount + " events " + (RecordTypes.TYPE_COUNT - 2)
                + " record types " + encoded.length + " bytes schema " + BinaryFormat.SCHEMA);
    }

    static CombatSnapshot sample() {
        CombatSnapshot s = new CombatSnapshot(8, 64);
        s.fightId = 0x1122334455667788L;
        s.windowId = -42L;
        s.role = RecordTypes.ROLE_DEFENDER;
        s.trigger = RecordTypes.TRIGGER_PROJECTILE;
        s.labelSource = RecordTypes.LABEL_STAFF_MARKER;
        s.validity = RecordTypes.VALID_LAG | RecordTypes.VALID_TELEPORT;
        s.sessionId = 123456789L;
        s.anonPlayerId = 987654321L;
        s.entityId = 4242;
        s.windowStartEpochMs = 1_700_000_000_000L;
        s.windowStartNanos = 1_000_000L;
        s.windowEndNanos = 9_000_000_000L;
        s.bootId = 99;
        s.tickStart = 100;
        s.tickEnd = 104;
        s.msptStartCenti = 1234;
        s.msptEndCenti = 1500;
        s.tpsCenti = 2000;
        s.pingMs = 42;
        s.jitterMs = 7;
        s.clientProtocol = 767;
        s.stateFlags = RecordTypes.FLAG_SPRINT | RecordTypes.FLAG_BEDROCK | RecordTypes.FLAG_CLIMBING;
        s.mainHand = 1;
        s.gamemode = 2;
        s.testAccount = true;
        s.healthMilli = 1950;
        s.absorptionMilli = 400;
        s.food = 18;
        s.totemCount = 3;
        s.hotbarTotem = true;
        s.attrDamage = 1000;
        s.attrSpeed = 4000;
        s.attrRange = 3000;
        s.attrMove = 100;
        s.attrJump = 420;
        s.attrScale = 1000;
        s.attrKbRes = 0;
        s.attrGravity = 80;
        s.effectsPacked0 = 0x123;
        s.effectsPacked1 = 0x456;
        s.clientBrand = "vanilla";
        s.serverId = "ffa";
        s.kit = "mace";
        s.world = "arena";
        s.mapId = "map1";
        s.mcVersion = "1.21.1";
        s.grimVersion = "2.3";
        s.peVersion = "2.14.0";
        s.taxonomyVersion = 1;
        s.taxonomyHash = 0xabcL;
        int[][] f = {
                {100, 1000, 2000, 3000, 4500, -1225, 1},
                {101, 1010, 2000, 3005, 4600, -1200, 0},
                {102, 1250, 2420, 3105, 6400, -900, 0},
                {103, 1300, 2300, 3200, 6410, -950, 1},
                {104, 1350, 2000, 3260, 6390, -1000, 1},
        };
        s.frameCount = f.length;
        for (int i = 0; i < f.length; i++) {
            s.fTick[i] = f[i][0];
            s.fNsOff[i] = 1000 * i + 3;
            s.fOrder[i] = (byte) (i + 1);
            s.fDur[i] = (short) (50 + i);
            s.fX[i] = f[i][1];
            s.fY[i] = f[i][2];
            s.fZ[i] = f[i][3];
            s.fYaw[i] = f[i][4];
            s.fPitch[i] = f[i][5];
            s.fFlags[i] = f[i][6] | (1 << 8) | (i << 2);
            s.fPose[i] = (byte) i;
            s.fSlot[i] = (byte) (i % 9);
            s.fHeldCat[i] = (byte) (i + 1);
            s.fHeldId[i] = 1000 + i;
            s.fOffCat[i] = 9;
            s.fOffId[i] = -7 - i;
            s.fPackets[i] = (byte) (2 + i);
            s.fPing[i] = (short) (40 + i);
            s.fJitter[i] = (short) i;
            s.fAck[i] = 500 + i;
            s.fGroundY[i] = 2000;
            s.fFall[i] = 10 * i;
            s.fVehType[i] = i == 2 ? 3 : 0;
            s.fVehId[i] = i == 2 ? 77 : 0;
        }
        for (int t = 1; t < RecordTypes.TYPE_COUNT; t++) {
            if (t == RecordTypes.FRAME) {
                continue;
            }
            long nanos = 1_000_000_000L + t * 100_000_000L;
            add(s, (byte) t, 100 + t % 5, nanos, t * 1_000_000_007L ^ 0x55AA55AAL, t * 11 + 1, -t * 13, t * 17, t * 19, -t * 23);
        }
        add(s, RecordTypes.ATTACK, 103, 1_250_000_000L, 55L, 9, 0, 0, 0, 0);
        add(s, RecordTypes.HELD_SLOT, 103, 1_240_000_000L, 2L, 3, 0, 0, 0, 0);
        add(s, RecordTypes.ATTACK, 104, 1_700_000_000L, 55L, 9, 0, 0, 0, 0);
        add(s, RecordTypes.RETOTEM, 104, 1_800_000_000L, 180_000_000L, RecordTypes.RETOTEM_CLICK, 2, 1, 1, 1);
        add(s, RecordTypes.RETOTEM, 104, 1_810_000_000L, 90_000_000L, RecordTypes.RETOTEM_FAILED, 0, 0, 0, 0);
        add(s, RecordTypes.JUMP_RESET, 104, 1_900_000_000L, 99L, 1, 0x40, 0x1F, 0, 0);
        add(s, RecordTypes.JUMP_RESET, 104, 1_950_000_000L, 99L, RecordTypes.JUMP_RESET_NONE, 0, 0, 0, 0);
        return s;
    }

    private static void add(CombatSnapshot s, byte type, int tick, long nanos, long a, int b, int c, int d, int e, int f) {
        int k = s.eventCount;
        if (!s.appendEvent(type, tick, nanos, a, b, c, d, e, f, 30 + k)) {
            throw new AssertionError("sample event cap");
        }
        s.eNsOff[k] = 7 * k;
        s.eOrder[k] = (byte) (k % 100);
    }

    private static void verify(CombatSnapshot s, byte[] encoded) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[4];
        in.readFully(magic);
        eq("magic", new String(magic, StandardCharsets.US_ASCII), "GML3");
        eq("schema", in.readUnsignedByte(), BinaryFormat.SCHEMA);
        eq("fightId", in.readLong(), s.fightId);
        eq("windowId", in.readLong(), s.windowId);
        eq("role", in.readByte(), s.role);
        eq("trigger", in.readByte(), s.trigger);
        eq("labelSource", in.readByte(), s.labelSource);
        eq("validity", in.readUnsignedShort(), s.validity);
        eq("sessionId", in.readLong(), s.sessionId);
        eq("anon", in.readLong(), s.anonPlayerId);
        eq("entityId", in.readInt(), s.entityId);
        eq("epochMs", in.readLong(), s.windowStartEpochMs);
        eq("startNanos", in.readLong(), s.windowStartNanos);
        eq("endNanos", in.readLong(), s.windowEndNanos);
        eq("bootId", in.readLong(), s.bootId);
        eq("tickStart", in.readInt(), s.tickStart);
        eq("tickEnd", in.readInt(), s.tickEnd);
        eq("msptStart", in.readShort(), s.msptStartCenti);
        eq("msptEnd", in.readShort(), s.msptEndCenti);
        eq("tps", in.readShort(), s.tpsCenti);
        eq("ping", in.readShort(), s.pingMs);
        eq("jitter", in.readShort(), s.jitterMs);
        eq("protocol", in.readShort(), s.clientProtocol);
        eq("stateFlags", in.readInt(), s.stateFlags);
        eq("mainHand", in.readByte(), s.mainHand);
        eq("gamemode", in.readByte(), s.gamemode);
        eq("testAccount", in.readByte(), s.testAccount ? 1 : 0);
        eq("health", in.readInt(), s.healthMilli);
        eq("absorption", in.readInt(), s.absorptionMilli);
        eq("food", in.readShort(), s.food);
        eq("totems", in.readShort(), s.totemCount);
        eq("hotbarTotem", in.readByte(), s.hotbarTotem ? 1 : 0);
        int[] attrs = {s.attrDamage, s.attrSpeed, s.attrRange, s.attrMove, s.attrJump, s.attrScale, s.attrKbRes, s.attrGravity,
                s.effectsPacked0, s.effectsPacked1};
        for (int i = 0; i < attrs.length; i++) {
            eq("attr" + i, in.readInt(), attrs[i]);
        }
        String[] strs = {s.clientBrand, s.serverId, s.kit, s.world, s.mapId, s.mcVersion, s.grimVersion, s.peVersion};
        for (int i = 0; i < strs.length; i++) {
            byte[] b = new byte[in.readUnsignedByte()];
            in.readFully(b);
            eq("str" + i, new String(b, StandardCharsets.UTF_8), strs[i]);
        }
        eq("taxVersion", in.readInt(), s.taxonomyVersion);
        eq("taxHash", in.readLong(), s.taxonomyHash);
        byte[] compressed = new byte[in.readInt()];
        in.readFully(compressed);
        eq("trailing", in.available(), 0);
        DataInputStream rec = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)));
        eq("frameCount", rec.readUnsignedShort(), s.frameCount);
        int ox = 0, oy = 0, oz = 0, oyaw = 0, opitch = 0;
        for (int i = 0; i < s.frameCount; i++) {
            String p = "frame" + i + ".";
            eq(p + "type", rec.readByte(), RecordTypes.FRAME);
            eq(p + "tick", rec.readInt(), s.fTick[i]);
            eq(p + "ns", rec.readInt(), s.fNsOff[i]);
            eq(p + "order", rec.readByte(), s.fOrder[i]);
            eq(p + "dur", rec.readShort(), s.fDur[i]);
            int x, y, z, yaw, pitch;
            if (i == 0) {
                x = rec.readInt();
                y = rec.readInt();
                z = rec.readInt();
                yaw = rec.readInt();
                pitch = rec.readInt();
            } else {
                x = ox + BinaryFormat.readVar(rec);
                y = oy + BinaryFormat.readVar(rec);
                z = oz + BinaryFormat.readVar(rec);
                yaw = oyaw + BinaryFormat.readVar(rec);
                pitch = opitch + BinaryFormat.readVar(rec);
            }
            ox = x;
            oy = y;
            oz = z;
            oyaw = yaw;
            opitch = pitch;
            eq(p + "x", x, s.fX[i]);
            eq(p + "y", y, s.fY[i]);
            eq(p + "z", z, s.fZ[i]);
            eq(p + "yaw", yaw, s.fYaw[i]);
            eq(p + "pitch", pitch, s.fPitch[i]);
            eq(p + "flags", rec.readInt(), s.fFlags[i]);
            eq(p + "pose", rec.readByte(), s.fPose[i]);
            eq(p + "slot", rec.readByte(), s.fSlot[i]);
            eq(p + "heldCat", rec.readByte(), s.fHeldCat[i]);
            eq(p + "heldId", rec.readInt(), s.fHeldId[i]);
            eq(p + "offCat", rec.readByte(), s.fOffCat[i]);
            eq(p + "offId", rec.readInt(), s.fOffId[i]);
            eq(p + "packets", rec.readByte(), s.fPackets[i]);
            eq(p + "ping", rec.readShort(), s.fPing[i]);
            eq(p + "jitter", rec.readShort(), s.fJitter[i]);
            eq(p + "ack", rec.readInt(), s.fAck[i]);
            eq(p + "groundY", rec.readInt(), s.fGroundY[i]);
            eq(p + "fall", rec.readInt(), s.fFall[i]);
            eq(p + "vehType", rec.readInt(), s.fVehType[i]);
            eq(p + "vehId", rec.readInt(), s.fVehId[i]);
        }
        eq("eventCount", rec.readUnsignedShort(), s.eventCount);
        boolean[] seen = new boolean[RecordTypes.TYPE_COUNT];
        for (int i = 0; i < s.eventCount; i++) {
            String p = "event" + i + ".";
            byte type = rec.readByte();
            eq(p + "type", type, s.eType[i]);
            seen[type] = true;
            eq(p + "tick", rec.readInt(), s.eTick[i]);
            eq(p + "ns", rec.readInt(), s.eNsOff[i]);
            eq(p + "order", rec.readByte(), s.eOrder[i]);
            eq(p + "nanos", rec.readLong(), s.eNanos[i]);
            eq(p + "a", rec.readLong(), s.eA[i]);
            eq(p + "b", rec.readInt(), s.eB[i]);
            eq(p + "c", rec.readInt(), s.eC[i]);
            eq(p + "d", rec.readInt(), s.eD[i]);
            eq(p + "e", rec.readInt(), s.eE[i]);
            eq(p + "f", rec.readInt(), s.eF[i]);
            eq(p + "ping", rec.readShort(), s.ePing[i]);
        }
        eq("body trailing", rec.read(), -1);
        for (int t = 1; t < RecordTypes.TYPE_COUNT; t++) {
            if (t != RecordTypes.FRAME && !seen[t]) {
                throw new AssertionError("record type " + t + " not covered");
            }
        }
    }

    private static void eq(String what, Object got, Object want) {
        Object g = got instanceof Byte b ? Integer.valueOf(b) : got instanceof Short sh ? Integer.valueOf(sh) : got;
        Object w = want instanceof Byte b ? Integer.valueOf(b) : want instanceof Short sh ? Integer.valueOf(sh) : want;
        if (g instanceof Number gn && w instanceof Number wn) {
            if (gn.longValue() == wn.longValue()) {
                return;
            }
        } else if (g.equals(w)) {
            return;
        }
        throw new AssertionError(what + ": got " + got + " want " + want);
    }

    static String json(CombatSnapshot s, double[] features) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"anon_player_id\": ").append(s.anonPlayerId)
                .append(",\n  \"session_id\": ").append(s.sessionId)
                .append(",\n  \"ping_ms\": ").append(s.pingMs)
                .append(",\n  \"jitter_ms\": ").append(s.jitterMs)
                .append(",\n  \"validity\": ").append(s.validity)
                .append(",\n  \"map_id\": \"").append(s.mapId).append('"')
                .append(",\n  \"frames\": [");
        for (int i = 0; i < s.frameCount; i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(String.format(Locale.ROOT,
                    "{\"tick\": %d, \"x_milli\": %d, \"y_milli\": %d, \"z_milli\": %d, \"yaw_centi\": %d, \"pitch_centi\": %d, \"flags\": %d, \"held_id\": %d, \"off_id\": %d, \"ack_tick\": %d, \"vehicle_id\": %d}",
                    s.fTick[i], s.fX[i], s.fY[i], s.fZ[i], s.fYaw[i], s.fPitch[i], s.fFlags[i], s.fHeldId[i], s.fOffId[i], s.fAck[i], s.fVehId[i]));
        }
        sb.append("\n  ],\n  \"events\": [");
        for (int i = 0; i < s.eventCount; i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(String.format(Locale.ROOT,
                    "{\"record_type\": %d, \"event_type\": \"%s\", \"tick\": %d, \"ns_offset\": %d, \"order_index\": %d, \"nanos\": %d, \"a\": %d, \"b\": %d, \"c\": %d, \"d\": %d, \"e\": %d, \"f\": %d, \"ping_ms\": %d}",
                    s.eType[i], RecordTypes.name(s.eType[i]), s.eTick[i], s.eNsOff[i], s.eOrder[i], s.eNanos[i], s.eA[i],
                    s.eB[i], s.eC[i], s.eD[i], s.eE[i], s.eF[i], s.ePing[i]));
        }
        sb.append("\n  ],\n  \"features\": {");
        for (int i = 0; i < FeatureExtractor.NAMES.length; i++) {
            double v = features[i];
            sb.append(i == 0 ? "\n    \"" : ",\n    \"").append(FeatureExtractor.NAMES[i]).append("\": ")
                    .append(Double.isFinite(v) ? Double.toString(v) : "null");
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }
}
