package dev.grimml.listeners;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.util.Vector;

// launch and player hits only primitives copied
public final class ProjectileListener implements Listener {

    private final GrimMLPlugin plugin;
    private final TickClock clock;

    public ProjectileListener(GrimMLPlugin plugin, TickClock clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile proj = event.getEntity();
        if (!(proj.getShooter() instanceof Player shooter)) {
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(shooter.getUniqueId());
        if (buf == null) {
            return;
        }
        long nanos = System.nanoTime();
        int tick = clock.tick();
        int kind = kind(proj, buf);
        Vector v = proj.getVelocity();
        buf.writeEvent(RecordTypes.PROJECTILE, tick, clock.nsOffset(nanos), buf.nextOrder(tick), nanos,
                0L, kind | (RecordTypes.PROJECTILE_LAUNCH << 8),
                (int) Math.round(v.getX() * 8000), (int) Math.round(v.getY() * 8000), (int) Math.round(v.getZ() * 8000),
                proj.getEntityId());
        boolean ranged = kind == ItemCategories.BOW || kind == ItemCategories.CROSSBOW || kind == ItemCategories.TRIDENT;
        if (ranged || buf.isCombatActive(nanos)) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_PROJECTILE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        Entity hit = event.getHitEntity();
        if (!(proj.getShooter() instanceof Player shooter) || !(hit instanceof Player target)) {
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(shooter.getUniqueId());
        PlayerRingBuffer tbuf = plugin.registry().get(target.getUniqueId());
        if (buf == null || tbuf == null) {
            return;
        }
        long nanos = System.nanoTime();
        int tick = clock.tick();
        var p = proj.getLocation();
        var t = target.getLocation();
        var s = shooter.getLocation();
        long dist = s.getWorld() == t.getWorld() ? Math.round(s.distance(t) * 1000) : 0L;
        buf.writeEvent(RecordTypes.PROJECTILE, tick, clock.nsOffset(nanos), buf.nextOrder(tick), nanos,
                tbuf.anonPlayerId, kind(proj, buf) | (RecordTypes.PROJECTILE_HIT << 8),
                (int) Math.round((p.getX() - t.getX()) * 1000), (int) Math.round((p.getY() - t.getY()) * 1000),
                (int) Math.round((p.getZ() - t.getZ()) * 1000), (int) Math.min(Integer.MAX_VALUE, dist));
    }

    private static int kind(Projectile proj, PlayerRingBuffer buf) {
        return switch (proj.getType().name()) {
            case "ARROW", "SPECTRAL_ARROW" -> buf.inv.heldCat() == ItemCategories.CROSSBOW || buf.inv.offCat() == ItemCategories.CROSSBOW
                    ? ItemCategories.CROSSBOW : ItemCategories.BOW;
            case "TRIDENT" -> ItemCategories.TRIDENT;
            case "ENDER_PEARL" -> ItemCategories.PEARL;
            case "WIND_CHARGE" -> ItemCategories.WIND_CHARGE;
            case "POTION", "SPLASH_POTION", "LINGERING_POTION" -> ItemCategories.POTION;
            default -> ItemCategories.OTHER;
        };
    }
}
