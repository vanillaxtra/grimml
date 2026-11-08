package dev.grimml.listeners;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

// i love larping chess
public final class DamageListener implements Listener {

    private final GrimMLPlugin plugin;
    private final TickClock clock;

    public DamageListener(GrimMLPlugin plugin, TickClock clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        PlayerRingBuffer vbuf = plugin.registry().get(victim.getUniqueId());
        if (vbuf == null) {
            return;
        }
        long nanos = System.nanoTime();
        int tick = clock.tick();
        int cause = event.getCause().ordinal();
        int raw = (int) Math.round(event.getDamage() * 100);
        int fin = (int) Math.round(event.getFinalDamage() * 100);
        boolean crit = event.isCritical();
        boolean blocked = event.getFinalDamage() < event.getDamage() && victim.isBlocking();
        Player attacker = event.getDamager() instanceof Player p ? p : null;
        long attackerAnon = 0L;
        int weapon = ItemCategories.FIST;
        int fall = 0;
        if (attacker != null) {
            PlayerRingBuffer abuf = plugin.registry().get(attacker.getUniqueId());
            if (abuf != null) {
                attackerAnon = abuf.anonPlayerId;
                weapon = abuf.inv.heldCat();
                fall = abuf.fallMilli;
                int ord = abuf.nextOrder(tick);
                abuf.writeEvent(RecordTypes.DAMAGE, tick, clock.nsOffset(nanos), ord, nanos,
                        vbuf.anonPlayerId, raw, fin, packFlags(crit, blocked, event), weapon, fall);
                abuf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_DAMAGE);
                writeWeaponDetail(abuf, vbuf, tick, nanos, ord, weapon, fall, fin);
            }
        }
        int ord = vbuf.nextOrder(tick);
        int hpBefore = (int) Math.round(victim.getHealth() * 100);
        vbuf.writeEvent(RecordTypes.DAMAGE, tick, clock.nsOffset(nanos), ord, nanos,
                attackerAnon, raw, fin, packFlags(crit, blocked, event) | (1 << 8), weapon, hpBefore);
        vbuf.healthMilli = hpBefore;
        vbuf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_DAMAGE);
        if (blocked) {
            vbuf.writeEvent(RecordTypes.SHIELD, tick, clock.nsOffset(nanos), ord, nanos, 2, weapon, 0, 0, 0, 0);
        }
        if (weapon == ItemCategories.AXE && blocked) {
            vbuf.writeEvent(RecordTypes.SHIELD, tick, clock.nsOffset(nanos), ord, nanos, 3, weapon, 100, 0, 0, 0);
        }
        if (weapon == ItemCategories.MACE && fall > 1500) {
            if (attacker != null) {
                PlayerRingBuffer abuf = plugin.registry().get(attacker.getUniqueId());
                if (abuf != null) {
                    abuf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_MACE);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnvDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(victim.getUniqueId());
        if (buf == null) {
            return;
        }
        long nanos = System.nanoTime();
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.DAMAGE, tick, clock.nsOffset(nanos), ord, nanos,
                0L, (int) Math.round(event.getDamage() * 100), (int) Math.round(event.getFinalDamage() * 100),
                event.getCause().ordinal(), 0, 0);
    }

    private void writeWeaponDetail(PlayerRingBuffer abuf, PlayerRingBuffer vbuf, int tick, long nanos, int ord,
                                   int weapon, int fall, int fin) {
        if (weapon == ItemCategories.MACE) {
            long sinceWind = abuf.lastWindNanos == 0L ? -1L : (nanos - abuf.lastWindNanos) / 1_000_000L;
            abuf.writeEvent(RecordTypes.COMBAT_DETAIL, tick, clock.nsOffset(nanos), ord, nanos,
                    vbuf.anonPlayerId, RecordTypes.DETAIL_MACE_SMASH, fall, fin, abuf.inv.heldEnch(),
                    (int) Math.min(Integer.MAX_VALUE, sinceWind));
        } else if (weapon == ItemCategories.SPEAR) {
            long held = abuf.useCat == ItemCategories.SPEAR && abuf.useStartNanos != 0L
                    ? (nanos - abuf.useStartNanos) / 1_000_000L : -1L;
            abuf.writeEvent(RecordTypes.COMBAT_DETAIL, tick, clock.nsOffset(nanos), ord, nanos,
                    vbuf.anonPlayerId, RecordTypes.DETAIL_SPEAR_HIT, (int) Math.min(Integer.MAX_VALUE, held),
                    fin, abuf.horizSpeedMilli, abuf.inv.heldEnch());
        }
    }

    private static int packFlags(boolean crit, boolean blocked, EntityDamageByEntityEvent event) {
        int f = 0;
        if (crit) f |= 1;
        if (blocked) f |= 2;
        if (event.getDamager() instanceof Player p && p.isSprinting()) f |= 4;
        return f;
    }

}
