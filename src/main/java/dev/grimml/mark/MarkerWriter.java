package dev.grimml.mark;

import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.BinaryFormat;
import dev.grimml.format.RecordTypes;
import dev.grimml.writer.RecordWriter;

import java.util.List;

public final class MarkerWriter {

    private MarkerWriter() {}

    public static void write(PlayerRingBuffer buf, TickClock clock, RecordWriter writer, long postNanos,
                             int action, int labelId, int clientId, int moduleId, List<String> kv, boolean interrupted) {
        long nanos = System.nanoTime();
        long wall = System.currentTimeMillis();
        int tick = clock.tick();
        int packed = action | (RecordTypes.SOURCE_STAFF << 8) | ((kv.size() & 0xff) << 16);
        if (interrupted) {
            packed = RecordTypes.MARKER_INTERRUPTED | (RecordTypes.SOURCE_STAFF << 8) | ((kv.size() & 0xff) << 16);
        }
        int settings = hashKv(kv);
        buf.writeEvent(RecordTypes.MARKER, tick, clock.nsOffset(nanos), buf.nextOrder(tick), nanos,
                wall, packed, labelId, clientId, moduleId, settings);
        for (String pair : kv) {
            int eq = pair.indexOf('=');
            String key = eq > 0 ? pair.substring(0, eq) : pair;
            String val = eq > 0 ? pair.substring(eq + 1) : "";
            buf.writeEvent(RecordTypes.MARKER_KV, tick, clock.nsOffset(nanos), buf.nextOrder(tick), nanos,
                    BinaryFormat.hashCheckName(key), BinaryFormat.hashCheckName(val), 0, 0, 0, 0);
        }
        buf.markCombat(nanos, postNanos, RecordTypes.TRIGGER_MARKER);
        String act = interrupted ? "interrupted" : (action == RecordTypes.MARKER_START ? "start" : "end");
        StringBuilder line = new StringBuilder(64);
        line.append("mark ").append(act).append(' ').append(Long.toUnsignedString(buf.anonPlayerId, 36))
                .append(' ').append(wall).append(' ').append(labelId).append(' ').append(clientId)
                .append(' ').append(moduleId);
        for (String pair : kv) {
            line.append(' ').append(pair);
        }
        writer.enqueueSessionLine(line.toString());
    }

    public static int hashKv(List<String> kv) {
        int h = 0x811c9dc5;
        for (String p : kv) {
            h ^= BinaryFormat.hashCheckName(p);
        }
        return h;
    }
}
