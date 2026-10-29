package dev.grimml.grim;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.BinaryFormat;
import dev.grimml.format.RecordTypes;
import org.bukkit.entity.Player;

public final class GrimFlagHook {

    private final GrimMLPlugin plugin;
    private final TickClock clock;
    private GrimPlugin grim;

    public GrimFlagHook(GrimMLPlugin plugin, TickClock clock) {
        this.plugin = plugin;
        this.clock = clock;
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
                    long nanos = System.nanoTime();
                    int tick = clock.tick();
                    int ord = buf.nextOrder(tick);
                    int vl = (int) check.getViolations();
                    buf.writeEvent(RecordTypes.GRIM_FLAG, tick, clock.nsOffset(nanos), ord, nanos,
                            checkHash, vl, 0, 0, 0, 0);
                    buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_FLAG);
                    plugin.writer().enqueueSessionLine("grim " + Integer.toHexString(checkHash) + " " + check.getCheckName());
                } catch (Throwable t) {
                    plugin.getLogger().warning("grim flag hook error: " + t.getMessage());
                }
                return cancelled;
            });
            try {
                bus.get(GrimPlayerSetbackEvent.class).onPlayerSetback(grim, (user, flags, x, y, z, t) -> {
                    Player player = plugin.getServer().getPlayer(user.getUniqueId());
                    if (player == null) {
                        return;
                    }
                    PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
                    if (buf == null) {
                        return;
                    }
                    long nanos = System.nanoTime();
                    int tick = clock.tick();
                    buf.orValidity(RecordTypes.VALID_SETBACK);
                    buf.writeEvent(RecordTypes.SETBACK, tick, clock.nsOffset(nanos), buf.nextOrder(tick), nanos,
                            flags, (int) (x * 1000), (int) (y * 1000), (int) (z * 1000), 0, 0);
                });
            } catch (Throwable ignored) {
            }
        } catch (NoClassDefFoundError | IllegalStateException ignored) {
            plugin.getLogger().info("grim not present; flag labels disabled");
        }
    }

    public boolean active() {
        return grim != null;
    }
}
