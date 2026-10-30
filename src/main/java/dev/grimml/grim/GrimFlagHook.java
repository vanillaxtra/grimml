package dev.grimml.grim;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
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
    private final GrimCheckCatalog catalog;
    private final FlagQueue flags;
    private GrimPlugin grim;
    private GrimAbstractAPI api;

    public GrimFlagHook(GrimMLPlugin plugin, TickClock clock, GrimCheckCatalog catalog, FlagQueue flags) {
        this.plugin = plugin;
        this.clock = clock;
        this.catalog = catalog;
        this.flags = flags;
    }

    public void enable() {
        try {
            api = GrimAPIProvider.get();
            grim = api.getGrimPlugin(plugin);
            EventBus bus = api.getEventBus();
            bus.get(FlagEvent.class).onFlag(grim, (user, check, verbose, cancelled) -> {
                try {
                    String name = check.getCheckName();
                    catalog.learn(name);
                    PlayerRingBuffer buf = plugin.registry().get(user.getUniqueId());
                    if (buf == null) {
                        return cancelled;
                    }
                    int checkHash = BinaryFormat.hashCheckName(name);
                    long nanos = System.nanoTime();
                    int tick = clock.tick();
                    int ord = buf.nextOrder(tick);
                    int vl = (int) check.getViolations();
                    buf.writeEvent(RecordTypes.GRIM_FLAG, tick, clock.nsOffset(nanos), ord, nanos,
                            checkHash, vl, 0, 0, 0, 0);
                    buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_FLAG);
                    plugin.writer().enqueueSessionLine("grim " + Integer.toHexString(checkHash) + " " + name);
                    if (flags != null) {
                        flags.offer(buf.anonPlayerId, buf.sessionId, checkHash, tick, nanos,
                                buf.pingMs, buf.jitterMs, buf.validity, cancelled ? 1 : 0,
                                buf.sessionStartNanos, buf.lastTeleportNanos, buf.lastWorldNanos,
                                buf.lastRespawnNanos, buf.lastSetbackNanos);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("grim flag hook error: " + t.getMessage());
                }
                return cancelled;
            });
            try {
                bus.get(GrimPlayerSetbackEvent.class).onPlayerSetback(grim, (user, flags, x, y, z, t) -> {
                    PlayerRingBuffer buf = plugin.registry().get(user.getUniqueId());
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

    // main thread only grim getGrimUser takes a player
    public void seedPlayer(Player player) {
        if (api == null || player == null) {
            return;
        }
        try {
            GrimUser user = api.getGrimUser(player);
            if (user == null) {
                return;
            }
            for (AbstractCheck check : user.getChecks()) {
                catalog.learn(check.getCheckName());
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean active() {
        return grim != null;
    }
}
