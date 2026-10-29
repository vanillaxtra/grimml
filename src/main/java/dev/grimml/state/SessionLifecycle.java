package dev.grimml.state;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.geyser.GeyserHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// creates a buffer with client metadata on join and drops it on quit
public final class SessionLifecycle implements Listener {

    private final SessionRegistry registry;
    private final GeyserHook geyser;

    public SessionLifecycle(SessionRegistry registry, GeyserHook geyser) {
        this.registry = registry;
        this.geyser = geyser;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerRingBuffer buf = registry.getOrCreate(player);
        buf.bedrock = geyser.isBedrock(player);
        buf.clientBrand = clientBrand(player);
        buf.clientProtocol = clientProtocol(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        registry.remove(event.getPlayer().getUniqueId());
    }

    private String clientBrand(Player player) {
        return player.getClientBrandName() == null ? "" : player.getClientBrandName();
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
