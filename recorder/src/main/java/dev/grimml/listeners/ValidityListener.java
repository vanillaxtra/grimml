package dev.grimml.listeners;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.RecordTypes;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class ValidityListener implements Listener {

    private final GrimMLPlugin plugin;

    public ValidityListener(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        mark(event.getEntity().getUniqueId(), RecordTypes.VALID_DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        mark(event.getPlayer().getUniqueId(), RecordTypes.VALID_RESPAWN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                || event.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN
                || event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            mark(event.getPlayer().getUniqueId(), RecordTypes.VALID_TELEPORT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        mark(event.getPlayer().getUniqueId(), RecordTypes.VALID_WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGamemode(PlayerGameModeChangeEvent event) {
        PlayerRingBuffer buf = plugin.registry().get(event.getPlayer().getUniqueId());
        if (buf != null) {
            buf.orValidity(RecordTypes.VALID_GAMEMODE);
            buf.gamemode = event.getNewGameMode().ordinal();
        }
    }

    private void mark(java.util.UUID uuid, int bit) {
        PlayerRingBuffer buf = plugin.registry().get(uuid);
        if (buf != null) {
            buf.orValidity(bit);
        }
    }
}
