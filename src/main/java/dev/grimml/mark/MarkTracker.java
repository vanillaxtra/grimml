package dev.grimml.mark;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarkTracker {

    public static final class Open {
        public final UUID uuid;
        public final long startNanos;
        public final long startEpochMs;
        public final int labelId;
        public final int clientId;
        public final int moduleId;
        public final int settingsHash;
        public final String[] kv;

        public Open(UUID uuid, long startNanos, long startEpochMs, int labelId, int clientId, int moduleId, int settingsHash, String[] kv) {
            this.uuid = uuid;
            this.startNanos = startNanos;
            this.startEpochMs = startEpochMs;
            this.labelId = labelId;
            this.clientId = clientId;
            this.moduleId = moduleId;
            this.settingsHash = settingsHash;
            this.kv = kv;
        }
    }

    private final ConcurrentHashMap<UUID, Open> open = new ConcurrentHashMap<>();

    public Open start(UUID uuid, long nanos, long epochMs, int labelId, int clientId, int moduleId, int settingsHash, String[] kv) {
        Open next = new Open(uuid, nanos, epochMs, labelId, clientId, moduleId, settingsHash, kv);
        return open.put(uuid, next);
    }

    public Open end(UUID uuid) {
        return open.remove(uuid);
    }

    public Open peek(UUID uuid) {
        return open.get(uuid);
    }

    public Open[] drainAll() {
        Open[] out = open.values().toArray(Open[]::new);
        open.clear();
        return out;
    }
}
