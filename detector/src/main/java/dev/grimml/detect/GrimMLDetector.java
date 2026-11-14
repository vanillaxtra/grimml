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

    private static final int ATTACK_CAP = 256;

    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
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
                (id, m, verbose) -> grim.flag(id, names.getOrDefault(id, id.toString()), m.checkName(), m.description(), verbose),
                scanSettings(), ATTACK_CAP);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            add(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> tick = Bukkit.getCurrentTick(), 0L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshExempt, 20L, 20L);

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
        tracks.put(p.getUniqueId(), new PlayerTrack(p.getEntityId(), frameCap, ATTACK_CAP));
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
        exempt.remove(id);
        names.remove(id);
        grim.forget(id);
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
                sender.sendMessage(String.format("§7tracked %d, windows scored %d, flags %d, last scan %.2f ms",
                        tracks.size(), scanner.windowsScored.get(), scanner.flagsRaised.get(), scanner.lastRunMs));
                if (!store.lastError().isEmpty()) {
                    sender.sendMessage("§c" + store.lastError());
                }
            }
            case "reload" -> {
                reloadConfig();
                grim.settings(bridgeSettings());
                scanner.settings(scanSettings());
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
