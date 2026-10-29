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

// records sprint and sneak toggles between sampler passes
public final class SprintSneakTracker implements Listener {

    private final SessionRegistry registry;

    public SprintSneakTracker(SessionRegistry registry) {
        this.registry = registry;
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
}
