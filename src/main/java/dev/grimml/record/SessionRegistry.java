package dev.grimml.record;

import dev.grimml.configs.PluginConfig;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {

    private final PluginConfig config;
    private final Map<UUID, PlayerRingBuffer> buffers = new ConcurrentHashMap<>();

    public SessionRegistry(PluginConfig config) {
        this.config = config;
    }

    public PlayerRingBuffer getOrCreate(Player player) {
        return buffers.computeIfAbsent(player.getUniqueId(), u -> {
            long sessionId = Anonymizer.newSessionId();
            long anonId = Anonymizer.anonId(player.getUniqueId(), config.anonymizeSalt());
            return new PlayerRingBuffer(sessionId, anonId, config.ringCapacity(), config.eventRingCapacity());
        });
    }

    public PlayerRingBuffer get(UUID uuid) {
        return buffers.get(uuid);
    }

    public void remove(UUID uuid) {
        buffers.remove(uuid);
    }

    public Iterable<PlayerRingBuffer> allBuffers() {
        return buffers.values();
    }

    public void clear() {
        buffers.clear();
    }
}
