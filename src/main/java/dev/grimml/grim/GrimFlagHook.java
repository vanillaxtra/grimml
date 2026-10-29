package dev.grimml.grim;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import dev.grimml.GrimMLPlugin;
import dev.grimml.format.BinaryFormat;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.RecordTypes;
import org.bukkit.entity.Player;


// records grim flags as weak labels only when grim is present

public final class GrimFlagHook {

    private final GrimMLPlugin plugin;
    private GrimPlugin grim;

    public GrimFlagHook(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        try {
            GrimAbstractAPI api = GrimAPIProvider.get();
            grim = api.getGrimPlugin(plugin);
            EventBus bus = api.getEventBus();
            bus.get(FlagEvent.class).onFlag(grim, (user, check, verbose, cancelled) -> {
                try {
                    Player player = plugin.getServer().getPlayer(user.getUniqueId());
                    if (player == null) {
                        return cancelled;
                    }
                    PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
                    if (buf == null) {
                        return cancelled;
                    }
                    int checkHash = BinaryFormat.hashCheckName(check.getCheckName());
                    int tick = player.getTicksLived();
                    long nanos = System.nanoTime();
                    int vl = (int) check.getViolations();
                    buf.writeEvent(RecordTypes.GRIM_FLAG, tick, nanos, checkHash, vl, 0, buf.pingMs);
                } catch (Throwable t) {
                    plugin.getLogger().warning("grim flag hook error: " + t.getMessage());
                }
                return cancelled;
            });
        } catch (NoClassDefFoundError | IllegalStateException ignored) {
            plugin.getLogger().info("grim not present; flag labels disabled");
        }
    }

    public boolean active() {
        return grim != null;
    }
}
