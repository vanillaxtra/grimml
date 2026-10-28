package dev.grimml.listeners;

import dev.grimml.GrimMLPlugin;
import dev.grimml.configs.PluginConfig;
import dev.grimml.hooks.GeyserHook;
import dev.grimml.record.PlayerRingBuffer;
import dev.grimml.record.RecordTypes;
import dev.grimml.record.SessionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

// captures metadata that cannot be derived from packets alone
// samples flags and ping periodically avoids heavy work
public final class StateMetadataListener implements Listener {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final PluginConfig config;
    private final GeyserHook geyser;
    private int tickTaskId = -1;

    public StateMetadataListener(GrimMLPlugin plugin, SessionRegistry registry, PluginConfig config, GeyserHook geyser) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.geyser = geyser;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerRingBuffer buf = registry.getOrCreate(player);
        buf.bedrock = geyser.isBedrock(player);
        buf.clientBrand = plugin.clientBrand(player);
        buf.clientProtocol = plugin.clientProtocol(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        registry.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent event) {
        updateStateFlags(event.getPlayer(), RecordTypes.FLAG_SPRINT, event.isSprinting());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        updateStateFlags(event.getPlayer(), RecordTypes.FLAG_SNEAK, event.isSneaking());
    }

    private void updateStateFlags(Player player, int flag, boolean set) {
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        if (set) {
            buf.stateFlags |= flag;
        } else {
            buf.stateFlags &= ~flag;
        }
        buf.writeEvent(RecordTypes.STATE, player.getTicksLived(), System.nanoTime(), buf.stateFlags, 0, 0, buf.pingMs);
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
        plugin.writer().pruneIfOverDiskCap();
        plugin.checkMsptPause();
    }
}
