package dev.grimml.buffer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// session scoped entity id to anon id no bukkit on the packet path
public final class EntityIndex {

    private final ConcurrentHashMap<Integer, Long> anonByEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> uuidByEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityByUuid = new ConcurrentHashMap<>();

    public void put(int entityId, UUID uuid, long anonId) {
        Integer prev = entityByUuid.put(uuid, entityId);
        if (prev != null && prev != entityId) {
            anonByEntity.remove(prev);
            uuidByEntity.remove(prev);
        }
        anonByEntity.put(entityId, anonId);
        uuidByEntity.put(entityId, uuid);
    }

    public void removeUuid(UUID uuid) {
        Integer id = entityByUuid.remove(uuid);
        if (id != null) {
            anonByEntity.remove(id);
            uuidByEntity.remove(id);
        }
    }

    public long anonOf(int entityId) {
        Long v = anonByEntity.get(entityId);
        return v == null ? 0L : v;
    }

    public UUID uuidOf(int entityId) {
        return uuidByEntity.get(entityId);
    }

    public void clear() {
        anonByEntity.clear();
        uuidByEntity.clear();
        entityByUuid.clear();
    }
}
