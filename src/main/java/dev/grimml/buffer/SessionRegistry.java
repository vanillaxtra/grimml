package dev.grimml.buffer;

import dev.grimml.clock.TickClock;
import dev.grimml.config.PluginConfig;
import dev.grimml.privacy.Anonymizer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {

    private final PluginConfig config;
    private final TickClock clock;
    private final Map<UUID, PlayerRingBuffer> buffers = new ConcurrentHashMap<>();
    public final EntityIndex entities = new EntityIndex();

    public SessionRegistry(PluginConfig config, TickClock clock) {
        this.config = config;
        this.clock = clock;
    }

    public PlayerRingBuffer getOrCreate(Player player) {
        return buffers.computeIfAbsent(player.getUniqueId(), u -> {
            long sessionId = Anonymizer.newSessionId();
            long anonId = Anonymizer.anonId(player.getUniqueId(), config.anonymizeSalt());
            int entityId = player.getEntityId();
            PlayerRingBuffer buf = new PlayerRingBuffer(sessionId, anonId, entityId, config.ringCapacity(), config.eventRingCapacity(), clock);
            entities.put(entityId, u, anonId);
            return buf;
        });
    }

    public PlayerRingBuffer get(UUID uuid) {
        return buffers.get(uuid);
    }

    public PlayerRingBuffer byEntityId(int entityId) {
        UUID u = entities.uuidOf(entityId);
        return u == null ? null : buffers.get(u);
    }

    public void remove(UUID uuid) {
        buffers.remove(uuid);
        entities.removeUuid(uuid);
    }

    public Iterable<PlayerRingBuffer> allBuffers() {
        return buffers.values();
    }

    public void clear() {
        buffers.clear();
        entities.clear();
    }
}
