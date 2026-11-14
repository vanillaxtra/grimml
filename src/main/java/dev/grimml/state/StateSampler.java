package dev.grimml.state;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimUser;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.clock.TickClock;
import dev.grimml.config.PluginConfig;
import dev.grimml.format.RecordTypes;
import dev.grimml.geyser.GeyserHook;
import dev.grimml.safety.MsptGuard;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// one tick heartbeat for the clock plus a staggered slice of bukkit state
// this is the only grimml code on the server thread every tick so it must stay cheap
public final class StateSampler {

    // flags this sampler owns: rebuilt from server state on every sample so they clear when the state ends
    // (they used to be OR-ed in and never cleared, so one swim marked a whole session as in water)
    static final int SAMPLED_FLAGS = RecordTypes.FLAG_VEHICLE | RecordTypes.FLAG_ELYTRA | RecordTypes.FLAG_BEDROCK
            | RecordTypes.FLAG_VANISHED | RecordTypes.FLAG_CREATIVE_FLIGHT | RecordTypes.FLAG_IN_WATER;

    private static final int GRIM_RETRY_TICKS = 200;

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final PluginConfig config;
    private final MsptGuard mspt;
    private final TickClock clock;
    private int tickTaskId = -1;
    private int cursor;

    // resolved once: looking an attribute up by name costs a registry search (and an exception on a miss)
    private final Attribute attrDamage = resolve("GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
    private final Attribute attrSpeed = resolve("GENERIC_ATTACK_SPEED", "ATTACK_SPEED");
    private final Attribute attrRange = resolve("PLAYER_ENTITY_INTERACTION_RANGE", "ENTITY_INTERACTION_RANGE");
    private final Attribute attrMove = resolve("GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED");
    private final Attribute attrJump = resolve("GENERIC_JUMP_STRENGTH", "JUMP_STRENGTH");
    private final Attribute attrScale = resolve("GENERIC_SCALE", "SCALE");
    private final Attribute attrKbRes = resolve("GENERIC_KNOCKBACK_RESISTANCE", "KNOCKBACK_RESISTANCE");
    private final Attribute attrGravity = resolve("GENERIC_GRAVITY", "GRAVITY");

    // main thread only
    private final Map<PotionEffectType, Integer> effectIds = new HashMap<>();
    private boolean grimPresent;
    private int grimRetryTick;

    public StateSampler(GrimMLPlugin plugin, SessionRegistry registry, PluginConfig config, GeyserHook geyser,
                        MsptGuard mspt, TickClock clock) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.mspt = mspt;
        this.clock = clock;
    }

    public void start() {
        grimPresent = Bukkit.getPluginManager().isPluginEnabled("GrimAC");
        tickTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
    }

    private void tick() {
        clock.onTick();
        if (clock.lastDurationMs() > 60) {
            for (PlayerRingBuffer buf : registry.allBuffers()) {
                buf.orValidity(RecordTypes.VALID_LAG);
            }
        }
        if (!grimPresent && clock.tick() >= grimRetryTick) {
            grimPresent = Bukkit.getPluginManager().isPluginEnabled("GrimAC");
            grimRetryTick = clock.tick() + GRIM_RETRY_TICKS;
        }
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        int n = online.size();
        if (n == 0) {
            mspt.check();
            return;
        }
        int take = Math.min(config.staggerPerTick(), n);
        if (online instanceof List<?> list) {
            // paper hands out an indexable view: touch only this tick's slice instead of copying everyone
            for (int i = 0; i < take; i++) {
                int at = (cursor + i) % n;
                if (at < list.size() && list.get(at) instanceof Player player) {
                    samplePlayer(player);
                }
            }
        } else {
            Player[] all = online.toArray(Player[]::new);
            n = all.length;
            take = Math.min(take, n);
            for (int i = 0; i < take; i++) {
                samplePlayer(all[(cursor + i) % n]);
            }
        }
        cursor = n == 0 ? 0 : (cursor + take) % n;
        mspt.check();
    }

    private void samplePlayer(Player player) {
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        int fresh = 0;
        Entity vehicle = player.isInsideVehicle() ? player.getVehicle() : null;
        if (vehicle != null) {
            fresh |= RecordTypes.FLAG_VEHICLE;
            buf.vehicleId = vehicle.getEntityId();
            buf.vehicleType = vehicle.getType().ordinal();
        } else {
            buf.vehicleId = 0;
            buf.vehicleType = 0;
        }
        if (player.isGliding()) fresh |= RecordTypes.FLAG_ELYTRA;
        // bedrock is fixed for a session and resolved at join (it costs reflection)
        if (buf.bedrock) fresh |= RecordTypes.FLAG_BEDROCK;
        if (player.isInvisible()) {
            fresh |= RecordTypes.FLAG_VANISHED;
            buf.orValidity(RecordTypes.VALID_VANISH);
        }
        if (player.isFlying()) fresh |= RecordTypes.FLAG_CREATIVE_FLIGHT;
        if (player.isInWater()) fresh |= RecordTypes.FLAG_IN_WATER;
        // sprint sneak blocking and item use belong to the packet path and the toggle events; leave them alone
        buf.updateLive(SAMPLED_FLAGS, fresh);
        buf.stateFlags = buf.liveFlags;

        int ping = player.getPing();
        buf.jitterMs = Math.abs(ping - buf.pingMs);
        buf.pingMs = ping;
        buf.healthMilli = (int) Math.round(player.getHealth() * 100);
        buf.absorptionMilli = (int) Math.round(player.getAbsorptionAmount() * 100);
        buf.food = player.getFoodLevel();
        buf.pose = player.getPose().ordinal();
        buf.gamemode = player.getGameMode().ordinal();
        buf.staff = player.hasPermission("grimml.admin") || player.isOp();
        buf.world = player.getWorld().getName();
        buf.attrDamage = attr(player, attrDamage);
        buf.attrSpeed = attr(player, attrSpeed);
        buf.attrRange = attr(player, attrRange);
        buf.attrMove = attr(player, attrMove);
        buf.attrJump = attr(player, attrJump);
        buf.attrScale = attr(player, attrScale);
        buf.attrKbRes = attr(player, attrKbRes);
        buf.attrGravity = attr(player, attrGravity);
        packEffects(player, buf);
        sampleGrim(player, buf);
    }

    private void sampleGrim(Player player, PlayerRingBuffer buf) {
        if (!grimPresent) {
            return;
        }
        try {
            GrimUser user = GrimAPIProvider.get().getGrimUser(player.getUniqueId());
            if (user == null) {
                return;
            }
            buf.lastAckTx = user.getLastTransactionReceived();
            buf.lastRttMs = user.getTransactionPing();
        } catch (Throwable t) {
            // grim missing or not ready: a throw per player per tick is expensive, so back off and look again later
            grimPresent = false;
            grimRetryTick = clock.tick() + GRIM_RETRY_TICKS;
        }
    }

    // old servers name these GENERIC_x, 1.21.3+ drops the prefix; whichever exists is kept for the whole run
    private static Attribute resolve(String... names) {
        for (String name : names) {
            try {
                Attribute a = Attribute.valueOf(name);
                if (a != null) {
                    return a;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int attr(Player player, Attribute a) {
        if (a == null) {
            return 0;
        }
        var inst = player.getAttribute(a);
        return inst == null ? 0 : (int) Math.round(inst.getValue() * 1000);
    }

    private void packEffects(Player player, PlayerRingBuffer buf) {
        Collection<PotionEffect> fx = player.getActivePotionEffects();
        int p0 = 0;
        int p1 = 0;
        int i = 0;
        for (PotionEffect e : fx) {
            int id = effectId(e.getType());
            int amp = Math.min(e.getAmplifier(), 7);
            int packed = (id & 0xFF) | (amp << 8) | ((e.getDuration() / 20) << 12);
            if (i == 0) p0 = packed;
            else if (i == 1) p1 = packed;
            else break;
            i++;
        }
        buf.effectsPacked0 = p0;
        buf.effectsPacked1 = p1;
    }

    private int effectId(PotionEffectType t) {
        Integer known = effectIds.get(t);
        if (known != null) {
            return known;
        }
        int id = classifyEffect(t);
        effectIds.put(t, id);
        return id;
    }

    private static int classifyEffect(PotionEffectType t) {
        if (t.equals(PotionEffectType.STRENGTH) || t.getKey().getKey().equals("strength")) return 1;
        if (t.equals(PotionEffectType.WEAKNESS)) return 2;
        if (t.equals(PotionEffectType.SPEED)) return 3;
        if (t.equals(PotionEffectType.SLOWNESS)) return 4;
        String key = t.getKey().getKey();
        if (key.contains("jump")) return 5;
        if (key.contains("slow_falling")) return 6;
        if (key.contains("levitation")) return 7;
        if (key.contains("resistance")) return 8;
        if (key.contains("blindness")) return 9;
        if (key.contains("haste")) return 10;
        if (key.contains("mining_fatigue")) return 11;
        return 0;
    }
}
