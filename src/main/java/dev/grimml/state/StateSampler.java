package dev.grimml.state;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.config.PluginConfig;
import dev.grimml.format.RecordTypes;
import dev.grimml.geyser.GeyserHook;
import dev.grimml.safety.MsptGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

// main thread heartbeat samples flags and ping then flushes combat windows and checks mspt
public final class StateSampler {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final PluginConfig config;
    private final GeyserHook geyser;
    private final MsptGuard mspt;
    private int tickTaskId = -1;

    public StateSampler(GrimMLPlugin plugin, SessionRegistry registry, PluginConfig config, GeyserHook geyser,
                        MsptGuard mspt) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.geyser = geyser;
        this.mspt = mspt;
    }

    public void start() {
        long intervalMs = Math.max(50L, Math.min(1000L, config.safetyCheckIntervalMs() / 2L));
        long intervalTicks = Math.max(1L, intervalMs / 50L);
        tickTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleAll, intervalTicks, intervalTicks).getTaskId();
    }

    public void stop() {
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
    }

    private void sampleAll() {
        long nanos = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerRingBuffer buf = registry.get(player.getUniqueId());
            if (buf == null) {
                continue;
            }
            int flags = 0;
            if (player.isSprinting()) flags |= RecordTypes.FLAG_SPRINT;
            if (player.isSneaking()) flags |= RecordTypes.FLAG_SNEAK;
            if (player.isInsideVehicle()) flags |= RecordTypes.FLAG_VEHICLE;
            if (player.isGliding()) flags |= RecordTypes.FLAG_ELYTRA;
            if (geyser.isBedrock(player)) flags |= RecordTypes.FLAG_BEDROCK;
            buf.stateFlags = flags;
            buf.pingMs = player.getPing();
            buf.writeEvent(RecordTypes.STATE, player.getTicksLived(), nanos, flags, 0, 0, buf.pingMs);

            plugin.writer().offerSnapshotIfCombat(buf);
        }
        mspt.check();
    }
}
