package dev.grimml.state;

import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.format.RecordTypes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

public final class SprintSneakTracker implements Listener {

    private final SessionRegistry registry;

    public SprintSneakTracker(SessionRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent event) {
        update(event.getPlayer(), RecordTypes.FLAG_SPRINT, event.isSprinting());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        update(event.getPlayer(), RecordTypes.FLAG_SNEAK, event.isSneaking());
    }

    private void update(Player player, int flag, boolean set) {
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        if (set) {
            buf.liveFlags |= flag;
        } else {
            buf.liveFlags &= ~flag;
        }
        buf.stateFlags = buf.liveFlags;
    }
}
