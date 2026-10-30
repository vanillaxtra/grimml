package dev.grimml.state;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.geyser.GeyserHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SessionLifecycle implements Listener {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final GeyserHook geyser;

    public SessionLifecycle(GrimMLPlugin plugin, SessionRegistry registry, GeyserHook geyser) {
        this.plugin = plugin;
        this.registry = registry;
        this.geyser = geyser;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerRingBuffer buf = registry.getOrCreate(player);
        buf.bedrock = geyser.isBedrock(player);
        buf.clientBrand = player.getClientBrandName() == null ? "" : player.getClientBrandName();
        buf.clientProtocol = clientProtocol(player);
        buf.staff = player.hasPermission("grimml.admin") || player.isOp();
        buf.gamemode = player.getGameMode().ordinal();
        if (plugin.testAccounts() != null && plugin.testAccounts().isTest(player.getUniqueId())) {
            buf.testAccount = true;
        }
        registry.entities.put(player.getEntityId(), player.getUniqueId(), buf.anonPlayerId);
        plugin.writer().enqueueSessionLine("join " + Long.toUnsignedString(buf.anonPlayerId, 36)
                + " " + Long.toUnsignedString(buf.sessionId, 36)
                + " " + buf.sessionStartEpoch
                + " " + buf.clientProtocol
                + " " + (buf.bedrock ? "bedrock" : "java"));
        if (plugin.grimHook() != null) {
            plugin.grimHook().seedPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf != null) {
            plugin.writer().enqueueSessionLine("quit " + Long.toUnsignedString(buf.anonPlayerId, 36)
                    + " " + Long.toUnsignedString(buf.sessionId, 36)
                    + " " + System.currentTimeMillis());
            plugin.closeOpen(player.getUniqueId(), buf, true);
            if (plugin.evidence() != null) {
                plugin.evidence().sessionEnd(buf.anonPlayerId, buf.sessionId);
            }
        }
        registry.remove(player.getUniqueId());
    }

    private int clientProtocol(Player player) {
        try {
            var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            return user == null ? 0 : user.getClientVersion().getProtocolVersion();
        } catch (Throwable t) {
            return 0;
        }
    }
}
