package dev.grimml.grim;

import dev.grimml.format.RecordTypes;

public final class LagFilter {

    private LagFilter() {}

    public static boolean skip(FlagQueue.Slot s, double mspt, GrimWeights w) {
        if (s == null || w == null) {
            return true;
        }
        if (s.ping > w.maxPingMs || s.jitter > w.maxJitterMs || mspt > w.maxMspt) {
            return true;
        }
        long settle = w.settleSeconds * 1_000_000_000L;
        if (recent(s.nanos, s.joinNanos, settle)
                || recent(s.nanos, s.teleportNanos, settle)
                || recent(s.nanos, s.worldNanos, settle)
                || recent(s.nanos, s.respawnNanos, settle)
                || recent(s.nanos, s.setbackNanos, settle)) {
            return true;
        }
        int bad = RecordTypes.VALID_LAG | RecordTypes.VALID_DEATH | RecordTypes.VALID_RESPAWN
                | RecordTypes.VALID_TELEPORT | RecordTypes.VALID_SETBACK | RecordTypes.VALID_WORLD;
        return (s.validity & bad) != 0;
    }

    static boolean recent(long now, long then, long window) {
        return then > 0 && now >= then && now - then < window;
    }
}
