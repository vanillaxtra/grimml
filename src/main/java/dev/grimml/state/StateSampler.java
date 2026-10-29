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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;

// one tick heartbeat for the clock plus a staggered slice of bukkit state
public final class StateSampler {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final PluginConfig config;
    private final GeyserHook geyser;
    private final MsptGuard mspt;
    private final TickClock clock;
    private int tickTaskId = -1;
    private int cursor;

    public StateSampler(GrimMLPlugin plugin, SessionRegistry registry, PluginConfig config, GeyserHook geyser,
                        MsptGuard mspt, TickClock clock) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.geyser = geyser;
        this.mspt = mspt;
        this.clock = clock;
    }

    public void start() {
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
        Player[] online = Bukkit.getOnlinePlayers().toArray(Player[]::new);
        int n = online.length;
        if (n == 0) {
            mspt.check();
            return;
        }
        int take = Math.min(config.staggerPerTick(), n);
        for (int i = 0; i < take; i++) {
            Player player = online[(cursor + i) % n];
            samplePlayer(player);
        }
        cursor = (cursor + take) % n;
        mspt.check();
    }

    private void samplePlayer(Player player) {
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        int flags = buf.liveFlags;
        if (player.isSprinting()) flags |= RecordTypes.FLAG_SPRINT;
        if (player.isSneaking()) flags |= RecordTypes.FLAG_SNEAK;
        if (player.isInsideVehicle()) flags |= RecordTypes.FLAG_VEHICLE;
        if (player.isGliding()) flags |= RecordTypes.FLAG_ELYTRA;
        if (geyser.isBedrock(player)) flags |= RecordTypes.FLAG_BEDROCK;
        if (player.isInvisible()) {
            flags |= RecordTypes.FLAG_VANISHED;
            buf.orValidity(RecordTypes.VALID_VANISH);
        }
        if (player.isFlying()) flags |= RecordTypes.FLAG_CREATIVE_FLIGHT;
        if (player.isInWater()) flags |= RecordTypes.FLAG_IN_WATER;
        buf.liveFlags = flags;
        buf.stateFlags = flags;
        int ping = player.getPing();
        int prev = buf.pingMs;
        buf.jitterMs = Math.abs(ping - prev);
        buf.pingMs = ping;
        buf.healthMilli = (int) Math.round(player.getHealth() * 100);
        buf.absorptionMilli = (int) Math.round(player.getAbsorptionAmount() * 100);
        buf.food = player.getFoodLevel();
        buf.pose = player.getPose().ordinal();
        buf.gamemode = player.getGameMode().ordinal();
        buf.staff = player.hasPermission("grimml.admin") || player.isOp();
        buf.bedrock = geyser.isBedrock(player);
        if (player.isInsideVehicle() && player.getVehicle() != null) {
            buf.vehicleId = player.getVehicle().getEntityId();
            buf.vehicleType = player.getVehicle().getType().ordinal();
        } else {
            buf.vehicleId = 0;
            buf.vehicleType = 0;
        }
        buf.attrDamage = attr(player, "GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
        buf.attrSpeed = attr(player, "GENERIC_ATTACK_SPEED", "ATTACK_SPEED");
        buf.attrRange = attr(player, "PLAYER_ENTITY_INTERACTION_RANGE", "ENTITY_INTERACTION_RANGE");
        buf.attrMove = attr(player, "GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED");
        buf.attrJump = attr(player, "GENERIC_JUMP_STRENGTH", "JUMP_STRENGTH");
        buf.attrScale = attr(player, "GENERIC_SCALE", "SCALE");
        buf.attrKbRes = attr(player, "GENERIC_KNOCKBACK_RESISTANCE", "KNOCKBACK_RESISTANCE");
        buf.attrGravity = attr(player, "GENERIC_GRAVITY", "GRAVITY");
        packEffects(player, buf);
        sampleGrim(player, buf);
        plugin.writer().setWorldName(player.getWorld().getName());
    }

    private void sampleGrim(Player player, PlayerRingBuffer buf) {
        try {
            GrimUser user = GrimAPIProvider.get().getGrimUser(player.getUniqueId());
            if (user == null) {
                return;
            }
            buf.lastAckTx = user.getLastTransactionReceived();
            buf.lastRttMs = user.getTransactionPing();
        } catch (Throwable ignored) {
        }
    }

    private static int attr(Player player, String... names) {
        for (String name : names) {
            try {
                Attribute a = Attribute.valueOf(name);
                var inst = player.getAttribute(a);
                if (inst != null) {
                    return (int) Math.round(inst.getValue() * 1000);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return 0;
    }

    private static void packEffects(Player player, PlayerRingBuffer buf) {
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

    private static int effectId(PotionEffectType t) {
        if (t.equals(PotionEffectType.STRENGTH) || t.getKey().getKey().equals("strength")) return 1;
        if (t.equals(PotionEffectType.WEAKNESS)) return 2;
        if (t.equals(PotionEffectType.SPEED)) return 3;
        if (t.equals(PotionEffectType.SLOWNESS)) return 4;
        if (t.getKey().getKey().contains("jump")) return 5;
        if (t.getKey().getKey().contains("slow_falling")) return 6;
        if (t.getKey().getKey().contains("levitation")) return 7;
        if (t.getKey().getKey().contains("resistance")) return 8;
        if (t.getKey().getKey().contains("blindness")) return 9;
        if (t.getKey().getKey().contains("haste")) return 10;
        if (t.getKey().getKey().contains("mining_fatigue")) return 11;
        return 0;
    }
}
