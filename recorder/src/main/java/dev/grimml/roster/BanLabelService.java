package dev.grimml.roster;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.BinaryFormat;
import dev.grimml.format.RecordTypes;
import dev.grimml.privacy.Anonymizer;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// suspect only never cheat_confirmed combat-reason bans backfill last n days
public final class BanLabelService {

    public static final String LABEL = "SUSPECT";
    private static final long DEDUPE_MS = 10_000L;

    private final GrimMLPlugin plugin;
    private final RosterStore store;
    private final ConcurrentHashMap<UUID, Long> last = new ConcurrentHashMap<>();

    public BanLabelService(GrimMLPlugin plugin, RosterStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void onCombatBan(UUID uuid, String reason, String source) {
        if (uuid == null || !plugin.config().banLabelsEnabled()) {
            return;
        }
        if (!plugin.config().combatReasons().matches(reason)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long prev = last.put(uuid, now);
        if (prev != null && now - prev < DEDUPE_MS) {
            return;
        }
        long anon = Anonymizer.anonId(uuid, plugin.config().anonymizeSalt());
        String anon36 = Long.toUnsignedString(anon, 36);
        long from = now - plugin.config().banBackfillDays() * 86_400_000L;
        try {
            store.append(uuid.toString(), anon36, LABEL, source, reason, now, from, plugin.config().serverId());
        } catch (IOException e) {
            plugin.getLogger().warning("roster write failed: " + e.getMessage());
            return;
        }
        plugin.writer().enqueueSessionLine("suspect " + anon36 + " " + from + " " + now + " " + BinaryFormat.hashCheckName(reason));
        PlayerRingBuffer buf = plugin.registry().get(uuid);
        if (buf != null) {
            long nanos = System.nanoTime();
            int tick = plugin.clock().tick();
            buf.writeEvent(RecordTypes.MARKER, tick, plugin.clock().nsOffset(nanos), buf.nextOrder(tick), nanos,
                    BinaryFormat.hashCheckName(LABEL), 1, BinaryFormat.hashCheckName(source), BinaryFormat.hashCheckName(reason), 0, 0);
        }
        plugin.getLogger().info("suspect backfill " + anon36 + " source " + source);
    }

    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.length() == 32) {
                s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                        + "-" + s.substring(16, 20) + "-" + s.substring(20);
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
