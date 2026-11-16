package dev.grimml.detect;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.grimml.detect.grim.GrimBridge;
import dev.grimml.detect.model.ModelSet;
import dev.grimml.detect.model.ModelStore;
import dev.grimml.detect.model.ModuleModel;
import dev.grimml.detect.scan.Scanner;
import dev.grimml.detect.track.PacketTap;
import dev.grimml.detect.track.PlayerTrack;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live ML checks for Grim. Threads:
 *  - netty (PacketTap): copies a few numbers per packet into the player's ring,
 *  - "GrimML-scan": features + model scoring every scan interval,
 *  - "GrimML-update": model downloads,
 *  - main thread: a tick counter, exemption refresh once a second, and sending alerts.
 */
public final class GrimMLDetector extends JavaPlugin implements Listener {

    private static final int ATTACK_CAP = 2048; // per-player event ring (schema 4 events, ~60-80/s in a fight)

    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerTrack> byEntity = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> exempt = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile int tick;
    private int frameCap;

    private ModelStore store;
    private GrimBridge grim;
    private Scanner scanner;
    private PacketListenerCommon tap;
    private ScheduledExecutorService scanExec;
    private ScheduledExecutorService updateExec;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        frameCap = Math.max(32, getConfig().getInt("window.frames", 160));

        store = new ModelStore(getDataFolder().toPath().resolve("models"), getLogger());
        store.loadLocal();
        grim = new GrimBridge(this, bridgeSettings());
        scanner = new Scanner(tracks, exempt, store::current,
                (id, check, description, verbose) -> grim.flag(id, names.getOrDefault(id, id.toString()), check, description, verbose),
                scanSettings(), gridSettings(), lungeSettings(), ATTACK_CAP);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            add(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> tick = Bukkit.getCurrentTick(), 0L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshExempt, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::sample, 2L, 2L);
        hookGrimTransactions();

        tap = PacketEvents.getAPI().getEventManager().registerListener(new PacketTap(tracks, () -> tick));

        scanExec = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GrimML-scan"));
        long every = Math.max(500L, getConfig().getLong("scan.interval-ms", 2000L));
        scanExec.scheduleAtFixedRate(scanner, every, every, TimeUnit.MILLISECONDS);

        updateExec = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GrimML-update"));
        long minutes = getConfig().getLong("models.check-every-minutes", 15L);
        if (getConfig().getBoolean("models.auto-update", true) && minutes > 0) {
            updateExec.scheduleWithFixedDelay(this::checkForModels, 5, minutes * 60, TimeUnit.SECONDS);
        }
        getLogger().info("Scanning every " + every + " ms off the main thread.");
    }

    @Override
    public void onDisable() {
        if (tap != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(tap);
        }
        if (scanExec != null) {
            scanExec.shutdownNow();
        }
        if (updateExec != null) {
            updateExec.shutdownNow();
        }
        tracks.clear();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    }

    private void checkForModels() {
        store.update(getConfig().getString("models.manifest-url", ""), set ->
                Bukkit.getScheduler().runTask(this, () -> getLogger().info("Now detecting with models v" + set.version())));
    }

    private Scanner.GridSettings gridSettings() {
        var c = getConfig();
        return new Scanner.GridSettings(c.getBoolean("aim-grid.enabled", true),
                c.getDouble("aim-grid.legit-off-rate", 0.01), c.getDouble("aim-grid.cheat-off-rate", 0.10),
                c.getDouble("aim-grid.alert-score", 20.0), c.getDouble("aim-grid.min-toward-bias", 0.05),
                c.getLong("aim-grid.alert-cooldown-ms", 15_000L));
    }

    private Scanner.LungeSettings lungeSettings() {
        var c = getConfig();
        return new Scanner.LungeSettings(c.getBoolean("lunge-rate.enabled", true),
                c.getDouble("lunge-rate.fast-ms", 300.0), c.getDouble("lunge-rate.min-ms", 75.0),
                c.getInt("lunge-rate.count", 6), c.getDouble("lunge-rate.window-ms", 30_000.0),
                c.getLong("lunge-rate.alert-cooldown-ms", 15_000L));
    }

    private Scanner.Settings scanSettings() {
        return new Scanner.Settings(frameCap,
                getConfig().getInt("scan.min-attacks", 2),
                getConfig().getInt("scan.min-frames", 40),
                getConfig().getLong("scan.fight-gap-ms", 20_000L));
    }

    private GrimBridge.Settings bridgeSettings() {
        Map<Integer, List<String>> punish = new HashMap<>();
        ConfigurationSection ps = getConfig().getConfigurationSection("punishments");
        if (ps != null && ps.getBoolean("enabled", false)) {
            ConfigurationSection at = ps.getConfigurationSection("at-violations");
            if (at != null) {
                for (String k : at.getKeys(false)) {
                    try {
                        punish.put(Integer.parseInt(k), new ArrayList<>(at.getStringList(k)));
                    } catch (NumberFormatException ignored) {
                        getLogger().warning("punishments.at-violations key '" + k + "' is not a number");
                    }
                }
            }
        }
        return new GrimBridge.Settings(
                getConfig().getString("alerts.format", ""),
                getConfig().getBoolean("alerts.console", true),
                getConfig().getString("alerts.webhook-url", ""),
                getConfig().getBoolean("alerts.use-grim-webhook", true),
                getConfig().getDouble("alerts.decay-per-minute", 0.05),
                punish);
    }

    // ---- players

    private void add(Player p) {
        PlayerTrack t = new PlayerTrack(p.getEntityId(), frameCap, ATTACK_CAP);
        t.peers = byEntity::get;
        tracks.put(p.getUniqueId(), t);
        byEntity.put(p.getEntityId(), t);
        names.put(p.getUniqueId(), p.getName());
        exempt.put(p.getUniqueId(), isExempt(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        add(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        tracks.remove(id);
        byEntity.remove(e.getPlayer().getEntityId());
        exempt.remove(id);
        names.remove(id);
        grim.forget(id);
    }

    // ---- schema 4 main-thread state (the recorder's StateSampler + DamageListener equivalents)

    private static final long OPPONENT_MEMORY_NANOS = 5_000_000_000L;

    /** Every 2 ticks: hotbar categories, gliding, Grim user, and each player's opponent (hit / hit by / nearest in 6). */
    private void sample() {
        long now = System.nanoTime();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerTrack t = tracks.get(p.getUniqueId());
            if (t == null) {
                continue;
            }
            int[] cats = new int[9];
            var inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                cats[i] = category(inv.getItem(i));
            }
            t.hotbarCats = cats;
            t.offCat = category(inv.getItemInOffHand());
            t.setLive(PlayerTrack.L_GLIDING, p.isGliding());
            if (t.grimUser == null) {
                try {
                    t.grimUser = ac.grim.grimac.api.GrimAPIProvider.get().getGrimUser(p.getUniqueId());
                } catch (Throwable ignored) {
                    // Grim not ready yet
                }
            }
            int opp = 0;
            int hit = t.lastTargetEntityId;
            if (hit != 0 && now - t.lastHitPacketNanos < OPPONENT_MEMORY_NANOS && byEntity.containsKey(hit)) {
                opp = hit;
            }
            int by = t.lastAttackerEntityId;
            if (opp == 0 && by != 0 && now - t.lastHurtNanos < OPPONENT_MEMORY_NANOS && byEntity.containsKey(by)) {
                opp = by;
            }
            int nearby = 0;
            int nearest = 0;
            double best = Double.MAX_VALUE;
            var loc = p.getLocation();
            for (Player o : p.getWorld().getPlayers()) {
                if (o == p || o.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                double d = o.getLocation().distanceSquared(loc);
                if (d <= 36.0) {
                    nearby++;
                    if (d < best) {
                        best = d;
                        nearest = o.getEntityId();
                    }
                }
            }
            t.opponentEntityId = opp != 0 ? opp : nearest;
            t.nearby = nearby;
        }
    }

    private static int category(org.bukkit.inventory.ItemStack item) {
        if (item == null) {
            return 0;
        }
        String n = item.getType().name();
        if (n.endsWith("_AXE")) {
            return PlayerTrack.CAT_AXE;
        }
        if (n.endsWith("_SPEAR")) {
            return PlayerTrack.CAT_SPEAR;
        }
        if (n.equals("MACE")) {
            return PlayerTrack.CAT_MACE;
        }
        return n.equals("SHIELD") ? PlayerTrack.CAT_SHIELD : 0;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        PlayerTrack t = tracks.get(victim.getUniqueId());
        if (t == null) {
            return;
        }
        t.lastHurtNanos = System.nanoTime();
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be && be.getDamager() instanceof Player attacker) {
            t.lastAttackerEntityId = attacker.getEntityId();
        }
    }

    /** Grim consumes its transaction replies before other listeners see them: take the pong from Grim's event. */
    private void hookGrimTransactions() {
        try {
            var api = ac.grim.grimac.api.GrimAPIProvider.get();
            api.getEventBus().get(ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent.class)
                    .onTransactionReceived(api.getGrimPlugin(this), (user, id, flag, time) -> {
                        PlayerTrack t = tracks.get(user.getUniqueId());
                        if (t != null) {
                            t.pong(tick, System.nanoTime(), id);
                        }
                    });
        } catch (Throwable t) {
            getLogger().warning("could not hook Grim transactions (backtrack and jump-reset features will be empty): " + t);
        }
    }

    private boolean isExempt(Player p) {
        if (p.hasPermission("grimml.detect.bypass")) {
            return true;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        // Bedrock players via Floodgate: different input, the models never saw them
        return getConfig().getBoolean("scan.skip-bedrock", true) && p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private void refreshExempt() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            exempt.put(p.getUniqueId(), isExempt(p));
        }
    }

    // ---- /grimmldetector

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                ModelSet set = store.current();
                sender.sendMessage("§bGrimML §7models v" + set.version() + (set.published().isEmpty() ? "" : " (" + set.published() + ")"));
                for (ModuleModel m : set.models()) {
                    sender.sendMessage("§7 - §f" + m.checkName() + (m.enabled() ? " §aon" : " §coff")
                            + String.format(" §7window>=%.2f, alert at %.0f%% of %d+ windows", m.windowThreshold(), m.alertShare() * 100, m.minWindows()));
                }
                Scanner.GridSettings g = gridSettings();
                sender.sendMessage("§7 - §f" + Scanner.GridSettings.CHECK + (g.enabled() ? " §aon" : " §coff")
                        + String.format(" §7alert at score %.0f, toward bias >= %.2f", g.h(), g.minBias()));
                Scanner.LungeSettings l = lungeSettings();
                sender.sendMessage("§7 - §f" + Scanner.LungeSettings.CHECK + (l.enabled() ? " §aon" : " §coff")
                        + String.format(" §7alert at %d swap-lunges under %.0f ms apart within %.0f s", l.count(), l.fastMs(), l.windowMs() / 1000));
                if (args.length > 1) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    String st = target == null ? null : scanner.gridStatus(target.getUniqueId());
                    sender.sendMessage("§7   " + args[1] + ": " + (st == null ? "no data yet" : st));
                }
                sender.sendMessage(String.format("§7tracked %d, windows scored %d, flags %d, last scan %.2f ms",
                        tracks.size(), scanner.windowsScored.get(), scanner.flagsRaised.get(), scanner.lastRunMs));
                if (!store.lastError().isEmpty()) {
                    sender.sendMessage("§c" + store.lastError());
                }
            }
            case "reload" -> {
                reloadConfig();
                grim.settings(bridgeSettings());
                scanner.settings(scanSettings(), gridSettings(), lungeSettings());
                sender.sendMessage("§bGrimML §7config reloaded (window size and scan interval need a restart).");
            }
            case "update" -> {
                sender.sendMessage("§bGrimML §7checking for new models...");
                updateExec.execute(() -> {
                    int before = store.current().version();
                    checkForModels();
                    int after = store.current().version();
                    String msg = after > before ? "§bGrimML §7updated to models v" + after
                            : (store.lastError().isEmpty() ? "§bGrimML §7already on the newest models (v" + after + ")" : "§c" + store.lastError());
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(msg));
                });
            }
            default -> sender.sendMessage("§7/" + label + " status | reload | update");
        }
        return true;
    }
}
