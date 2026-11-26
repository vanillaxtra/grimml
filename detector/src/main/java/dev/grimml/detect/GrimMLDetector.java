package dev.grimml.detect;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.grimml.detect.grim.GrimBridge;
import dev.grimml.detect.grim.Punishments;
import dev.grimml.detect.history.AlertHistory;
import dev.grimml.detect.telemetry.Telemetry;
import dev.grimml.detect.model.ModelSet;
import dev.grimml.detect.model.ModelStore;
import dev.grimml.detect.model.ModuleModel;
import dev.grimml.detect.scan.Scanner;
import dev.grimml.detect.track.PacketTap;
import dev.grimml.detect.track.PlayerTrack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GrimMLDetector extends JavaPlugin implements Listener, TabCompleter {

    private static final int ATTACK_CAP = 2048; // events kept per player (about 60-80 a second in a fight)
    // bump this when config.yml keys are added, renamed or change meaning
    public static final int CONFIG_VERSION = 4;

    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerTrack> byEntity = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> exempt = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    // the checks each player bypasses with grimml.bypass.<check> 
    private final Map<UUID, Set<String>> bypass = new ConcurrentHashMap<>();
    // players who turned alerts off with /grimml alerts 
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();
    // when each staff member last left (this run), so alerts on join show only what they missed
    private final Map<UUID, Long> staffLeft = new ConcurrentHashMap<>();
    private volatile int tick;
    private int frameCap;

    private ModelStore store;
    private GrimBridge grim;
    private Scanner scanner;
    private PacketListenerCommon tap;
    private dev.grimml.detect.overlay.Overlay overlay;
    private AlertHistory history;
    private Telemetry telemetry; // null if its folder could not be made
    private ScheduledExecutorService scanExec;
    private ScheduledExecutorService updateExec;
    private dev.grimml.detect.update.ModelNotices modelNotices;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checkConfigVersion();
        frameCap = Math.max(32, getConfig().getInt("window.frames", 160));

        store = new ModelStore(getDataFolder().toPath().resolve("models"), getLogger());
        store.loadLocal();
        modelNotices = new dev.grimml.detect.update.ModelNotices(this,
                () -> GrimBridge.colour(getConfig().getString("alerts.prefix", "&bGrimML &8»")));
        grim = new GrimBridge(this, bridgeSettings(),
                p -> p.hasPermission("grimml.alerts") && !alertsOff.contains(p.getUniqueId()));
        history = new AlertHistory(getDataFolder().toPath().resolve("history"),
                getConfig().getBoolean("history.enabled", true), getConfig().getInt("history.keep-days", 14), getLogger());
        grim.history(history::record);
        try {
            String[] featureNames = java.util.stream.Stream.concat(
                    java.util.Arrays.stream(dev.grimml.detect.features.Features.NAMES),
                    java.util.Arrays.stream(dev.grimml.detect.features.Schema4Features.NAMES)).toArray(String[]::new);
            telemetry = new Telemetry(getDataFolder().toPath().resolve("telemetry"), telemetrySettings(), getLogger(),
                    getPluginMeta().getVersion(), Bukkit.getMinecraftVersion(), () -> store.current().version(),
                    featureNames);
            if (telemetry.enabled()) {
                getLogger().info("Telemetry on: anonymous fight data is shared with the GrimML developers "
                        + "(telemetry.enabled in config.yml). Thank you!");
            }
        } catch (Exception ex) {
            getLogger().warning("telemetry is off: " + ex.getMessage());
        }
        // old days are deleted at start and every 6 hours
        Bukkit.getScheduler().runTaskTimer(this, () -> history.prune(System.currentTimeMillis()), 20L, 6 * 72_000L);
        overlay = new dev.grimml.detect.overlay.Overlay(overlaySettings(),
                id -> {
                    PlayerTrack t = tracks.get(id);
                    return t == null ? null : t.entityId;
                },
                id -> {
                    PlayerTrack t = tracks.get(id);
                    return t == null ? null : t.position();
                },
                GrimBridge::friendly,
                () -> Bukkit.getUnsafe().nextEntityId()); // an atomic counter, safe off the main thread
        scanner = new Scanner(tracks, exempt, store::current, new Scanner.Sink() {
            @Override
            public void flag(UUID id, String check, String description, double confidence, String verbose,
                             int flagged, int windows) {
                if (!bypasses(id, check)) {
                    grim.flag(id, names.getOrDefault(id, id.toString()), check, description, confidence, verbose,
                            flagged, windows);
                    overlay.alert(id, check, confidence, flagged, windows);
                    if (telemetry != null) {
                        telemetry.alert(id, System.currentTimeMillis(), check, confidence, flagged, windows);
                    }
                }
            }

            @Override
            public boolean collect() {
                return telemetry != null && telemetry.enabled();
            }

            @Override
            public void window(UUID id, double[] features, List<String> checks, double[] scores, double[] thresholds,
                               int attacks, int events) {
                if (!bypasses(id, "telemetry")) {
                    telemetry.window(id, System.currentTimeMillis(), features, checks, scores, thresholds, attacks,
                            events, bypasses(id, "trusted"));
                }
            }

            @Override
            public void live(UUID id, String check, int windows, int flagged, double meanScore) {
                if (!bypasses(id, check)) {
                    overlay.live(id, check, windows, flagged, meanScore);
                }
            }

            @Override
            public void scanned() {
                overlay.refresh();
                if (telemetry != null) {
                    telemetry.tick(System.currentTimeMillis());
                }
            }
        }, scanSettings(), gridSettings(), lungeSettings(), ATTACK_CAP);
        var cmd = getCommand("grimml");
        if (cmd != null) {
            cmd.setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            add(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> tick = Bukkit.getCurrentTick(), 0L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshExempt, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::sample, 2L, 2L);
        hookGrimTransactions();

        tap = PacketEvents.getAPI().getEventManager().registerListener(new PacketTap(tracks, () -> tick));
        PacketEvents.getAPI().getEventManager().registerListener(overlay);

        scanExec = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GrimML-scan"));
        long every = Math.max(500L, getConfig().getLong("scan.interval-ms", 2000L));
        scanExec.scheduleAtFixedRate(scanner, every, every, TimeUnit.MILLISECONDS);

        updateExec = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GrimML-update"));
        long minutes = getConfig().getLong("models.check-every-minutes", 15L);
        if (getConfig().getBoolean("models.auto-update", true) && minutes > 0) {
            updateExec.scheduleWithFixedDelay(this::checkForModels, 5, minutes * 60, TimeUnit.SECONDS);
        }
        getLogger().info("Scanning every " + every + " ms off the main thread.");
        getLogger().info("The ML checks are in beta and can flag legit players: use their alerts to decide who to "
                + "watch, and keep punishments for them off until the alerts look right on your server.");
        if (getConfig().getBoolean("update-checker", true)) {
            new dev.grimml.detect.update.UpdateChecker(this);
        }
        setupMetrics();
    }

    private void checkConfigVersion() {
        int have = getConfig().getInt("config-version", 0);
        if (have < CONFIG_VERSION) {
            getLogger().warning("config.yml is version " + have + ", this GrimML expects " + CONFIG_VERSION + ". New "
                    + "settings use their defaults; compare with the config.yml in the jar (or delete yours to "
                    + "regenerate it), then set config-version: " + CONFIG_VERSION + ".");
        } else if (have > CONFIG_VERSION) {
            getLogger().warning("config.yml is version " + have + ", newer than this GrimML (" + CONFIG_VERSION
                    + "). Settings it does not know are ignored.");
        }
    }

    @Override
    public void onDisable() {
        if (tap != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(tap);
        }
        if (overlay != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(overlay);
            overlay.shutdown();
        }
        if (scanExec != null) {
            scanExec.shutdownNow();
        }
        if (updateExec != null) {
            updateExec.shutdownNow();
        }
        if (grim != null) {
            grim.shutdown();
        }
        if (history != null) {
            history.shutdown();
        }
        if (telemetry != null) {
            telemetry.shutdown();
        }
        tracks.clear();
    }

    private void setupMetrics() {
        try {
            var metrics = new org.bstats.bukkit.Metrics(this, 35441);
            metrics.addCustomChart(new org.bstats.charts.SimplePie("models_version",
                    () -> Integer.toString(store.current().version())));
            metrics.addCustomChart(new org.bstats.charts.SimplePie("punishments",
                    () -> grim.groups().isEmpty() ? "off" : "on"));
            metrics.addCustomChart(new org.bstats.charts.SimplePie("webhook",
                    () -> getConfig().getBoolean("webhook.enabled", true) ? "on" : "off"));
        } catch (Throwable t) {
            getLogger().warning("bStats failed to start: " + t.getMessage());
        }
    }

    // a low priority background thread that does not stop the server from shutting down
    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    }

    // checks for new models on the update thread and tells staff if they changed (see ModelNotices)
    private void checkForModels() {
        int before = store.current().version();
        store.update(getConfig().getString("models.manifest-url", ""), set ->
                Bukkit.getScheduler().runTask(this, () -> {
                    getLogger().info("Now detecting with models v" + set.version());
                    modelNotices.updated(before, set.version());
                }));
    }


    // the aim-grid section
    private Scanner.GridSettings gridSettings() {
        var c = getConfig();
        return new Scanner.GridSettings(c.getBoolean("aim-grid.enabled", true),
                c.getDouble("aim-grid.legit-off-rate", 0.01), c.getDouble("aim-grid.cheat-off-rate", 0.10),
                c.getDouble("aim-grid.alert-score", 20.0), c.getDouble("aim-grid.min-toward-bias", 0.05),
                c.getLong("aim-grid.alert-cooldown-ms", 15_000L),
                c.getDouble("aim-grid.baseline-margin", 1.5), c.getDouble("aim-grid.baseline-max", 0.08),
                c.getInt("aim-grid.baseline-samples", 100), c.getDouble("aim-grid.max-steps", 20.0));
    }

    private Scanner.LungeSettings lungeSettings() {
        var c = getConfig();
        return new Scanner.LungeSettings(c.getBoolean("lunge-rate.enabled", true),
                c.getDouble("lunge-rate.fast-ms", 300.0), c.getDouble("lunge-rate.min-ms", 75.0),
                c.getInt("lunge-rate.count", 6), c.getDouble("lunge-rate.window-ms", 30_000.0),
                c.getBoolean("lunge-hold.enabled", true), c.getInt("lunge-hold.count", 5), c.getInt("lunge-hold.of", 10),
                c.getBoolean("swap-order.enabled", true), c.getInt("swap-order.count", 2),
                c.getDouble("swap-order.window-ms", 60_000.0),
                c.getBoolean("axe-hold.enabled", false), c.getInt("axe-hold.count", 6), c.getInt("axe-hold.of", 10),
                c.getBoolean("combo-repeat.stun", false), c.getBoolean("combo-repeat.slam", false),
                c.getBoolean("combo-repeat.web", true), c.getInt("combo-repeat.count", 8), c.getInt("combo-repeat.of", 10),
                c.getLong("lunge-rate.alert-cooldown-ms", 15_000L));
    }

    // the scan section
    private Scanner.Settings scanSettings() {
        return new Scanner.Settings(frameCap,
                getConfig().getInt("scan.min-attacks", 2),
                getConfig().getInt("scan.min-frames", 40),
                getConfig().getLong("scan.fight-gap-ms", 20_000L),
                getConfig().getInt("scan.fight-block-windows", 120),
                getConfig().getInt("scan.fight-min-windows", 20),
                getConfig().getDouble("scan.early-alert-z", 3.0),
                getConfig().getInt("scan.early-alert-min-windows", 10));
    }

    // the overlay section (MiniMessage line formats)
    private dev.grimml.detect.overlay.Overlay.Settings overlaySettings() {
        var c = getConfig();
        int bg;
        try {
            bg = (int) Long.parseLong(c.getString("overlay.background", "40000000").replace("#", ""), 16);
        } catch (NumberFormatException e) {
            getLogger().warning("overlay.background must be an ARGB hex colour like 40000000");
            bg = 0x40000000;
        }
        return new dev.grimml.detect.overlay.Overlay.Settings(c.getBoolean("overlay.enabled", true),
                (float) c.getDouble("overlay.height", 0.55), (float) c.getDouble("overlay.scale", 0.8),
                Math.max(1, c.getInt("overlay.max-lines", 4)),
                Math.max(1L, c.getLong("overlay.keep-minutes", 5L)) * 60_000L,
                c.getBoolean("overlay.show-watching", true), c.getBoolean("overlay.see-through", false), bg,
                c.getString("overlay.flagged", "<red><check></red> <white><confidence>%</white> <gray><flagged>/<windows>"),
                c.getString("overlay.watching", "<yellow><check></yellow> <white><confidence>%</white> <gray><flagged>/<windows>"),
                c.getString("overlay.rule", "<red><check></red> <white><confidence>%</white> <gray>x<count>"));
    }

    private boolean bypasses(UUID id, String check) {
        Set<String> b = bypass.get(id);
        return b != null && b.contains(check.toLowerCase(Locale.ROOT));
    }

    private Telemetry.Settings telemetrySettings() {
        var c = getConfig();
        return new Telemetry.Settings(c.getBoolean("telemetry.enabled", false),
                Telemetry.URL,
                c.getInt("telemetry.upload-every-minutes", 60),
                Telemetry.SAMPLING);
    }

    // the alerts, webhook and punishments sections
    private GrimBridge.Settings bridgeSettings() {
        var c = getConfig();
        List<Punishments.Group> punish = punishmentGroups(c.getConfigurationSection("punishments"),
                c.getDouble("alerts.decay-per-minute", 0.05));
        var webhook = new GrimBridge.Webhook(
                c.getBoolean("webhook.enabled", true),
                c.getString("webhook.url", ""),
                c.getBoolean("webhook.use-grim-webhook", true),
                c.getString("webhook.embed-title", "**GrimML Alert**"),
                c.getString("webhook.embed-color", "#00FFFF"),
                c.getString("webhook.embed-thumbnail-url", "https://crafthead.net/helm/%uuid%"),
                c.getString("webhook.embed-image-url", ""),
                c.getString("webhook.embed-footer-text", "GrimML v%grimml_version%"),
                c.getString("webhook.embed-footer-url", "https://grim.ac/images/grim.png"),
                c.getBoolean("webhook.include-timestamp", true),
                c.getBoolean("webhook.include-verbose", true),
                c.isList("webhook.violation-content") ? c.getStringList("webhook.violation-content") : List.of(
                        "**Player**: `%player%`", "**Check**: %check_name%", "**Violations**: %violations%",
                        "**Client Version**: %version%", "**Brand**: `%brand%`", "**Ping**: %ping%", "**TPS**: %tps%"));
        return new GrimBridge.Settings(
                c.getString("alerts.prefix", "&bGrimML &8»"),
                c.getString("alerts.format", "%prefix% &f%player% &7flagged &b%check% &8(&f%confidence%%&8) &7x%vl%"),
                c.isList("alerts.hover") ? c.getStringList("alerts.hover") : List.of(),
                c.getString("alerts.click-command", ""),
                c.getBoolean("alerts.console", true),
                c.getString("alerts.sound.name", "block.note_block.pling"),
                (float) c.getDouble("alerts.sound.volume", 1.0),
                (float) c.getDouble("alerts.sound.pitch", 1.0),
                c.getDouble("alerts.decay-per-minute", 0.05),
                punish, webhook, punishmentWebhook(c, webhook),
                c.getInt("punishments.delay-seconds.min", 0),
                c.getInt("punishments.delay-seconds.max", 0));
    }

    private List<Punishments.Group> punishmentGroups(ConfigurationSection ps, double defaultDecay) {
        List<Punishments.Group> out = new ArrayList<>();
        if (ps == null || !ps.getBoolean("enabled", false)) {
            return out;
        }
        ConfigurationSection groups = ps.getConfigurationSection("groups");
        if (groups != null) {
            for (String name : groups.getKeys(false)) {
                ConfigurationSection g = groups.getConfigurationSection(name);
                if (g == null) {
                    getLogger().warning("punishments.groups." + name + " is not a section, skipped");
                    continue;
                }
                Set<String> checks = new HashSet<>();
                for (String ch : g.getStringList("checks")) {
                    checks.add(ch.trim().toLowerCase(Locale.ROOT));
                }
                Set<String> known = new HashSet<>();
                for (String k : allChecks()) {
                    known.add(k.toLowerCase(Locale.ROOT));
                    known.add(GrimBridge.friendly(k).toLowerCase(Locale.ROOT));
                }
                for (String ch : checks) {
                    if (!ch.equals("*") && !known.contains(ch)) {
                        getLogger().warning("punishments.groups." + name + ": unknown check '" + ch + "' (known: "
                                + String.join(", ", allChecks()) + ", their short names, or *)");
                    }
                }
                if (checks.isEmpty()) {
                    getLogger().warning("punishments.groups." + name + ".checks is empty, skipped");
                    continue;
                }
                var at = thresholds(g.getConfigurationSection("at-violations"), "punishments.groups." + name);
                if (!at.isEmpty() && g.getBoolean("enabled", true)) {
                    out.add(new Punishments.Group(name, checks, g.getDouble("decay-per-minute", defaultDecay), at));
                }
            }
        } else if (ps.isConfigurationSection("at-violations")) {
            var at = thresholds(ps.getConfigurationSection("at-violations"), "punishments");
            if (!at.isEmpty()) {
                getLogger().info("punishments.at-violations is the old layout: counted over every check together. "
                        + "Move it into punishments.groups (see the default config) to punish per check.");
                out.add(new Punishments.Group("all", Set.of("*"), defaultDecay, at));
            }
        }
        return out;
    }

    private java.util.TreeMap<Integer, List<String>> thresholds(ConfigurationSection at, String path) {
        var out = new java.util.TreeMap<Integer, List<String>>();
        if (at == null) {
            return out;
        }
        for (String k : at.getKeys(false)) {
            try {
                int n = Integer.parseInt(k.trim());
                List<String> cmds = new ArrayList<>();
                for (String cmd : at.isList(k) ? at.getStringList(k) : List.of(at.getString(k, ""))) {
                    String t = cmd.trim();
                    if (t.startsWith("/")) {
                        t = t.substring(1);
                    }
                    if (!t.isEmpty()) {
                        cmds.add(t);
                    }
                }
                if (n > 0) {
                    out.put(n, cmds);
                } else {
                    getLogger().warning(path + ".at-violations." + k + " must be 1 or more");
                }
            } catch (NumberFormatException e) {
                getLogger().warning(path + ".at-violations key '" + k + "' is not a number");
            }
        }
        return out;
    }

    private static GrimBridge.Webhook punishmentWebhook(org.bukkit.configuration.file.FileConfiguration c, GrimBridge.Webhook w) {
        if (!c.getBoolean("webhook.punishments.enabled", true)) {
            return null;
        }
        return new GrimBridge.Webhook(true, w.url(), w.useGrimWebhook(),
                c.getString("webhook.punishments.embed-title", "**GrimML Punishment**"),
                c.getString("webhook.punishments.embed-color", "#FF0000"),
                w.thumbnailUrl(), w.imageUrl(), w.footerText(), w.footerUrl(), w.includeTimestamp(),
                c.getBoolean("webhook.punishments.include-verbose", true),
                c.isList("webhook.punishments.content") ? c.getStringList("webhook.punishments.content") : List.of(
                        "**Player**: `%player%`", "**Punishment**: %group% at %group_vl% violations",
                        "**Last check**: %check_name%", "**Commands**: `%commands%`",
                        "**Client Version**: %version%", "**Ping**: %ping%"));
    }

    private List<String> allChecks() {
        List<String> out = new ArrayList<>();
        for (ModuleModel m : store.current().models()) {
            out.add(m.checkName());
        }
        out.add(Scanner.GridSettings.CHECK);
        out.addAll(List.of(Scanner.LungeSettings.CHECKS));
        return out;
    }


    // starts tracking a player
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
        Player p = e.getPlayer();
        add(p);
        if (getConfig().getBoolean("history.on-join.enabled", true) && p.hasPermission("grimml.alerts")) {
            // after the join messages and MOTD, so it is not buried
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) {
                    missedAlerts(p);
                }
            }, 60L);
        }
    }

    // a ban for telemetry: the server's own ban list (the kick cause), or a kick whose message says "ban", which is how
    // ban plugins kick (UNVERIFIED for each plugin). The message itself is never sent.
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(org.bukkit.event.player.PlayerKickEvent e) {
        if (telemetry == null || !telemetry.enabled() || bypasses(e.getPlayer().getUniqueId(), "telemetry")) {
            return;
        }
        var cause = e.getCause();
        String how = null;
        if (cause == org.bukkit.event.player.PlayerKickEvent.Cause.BANNED
                || cause == org.bukkit.event.player.PlayerKickEvent.Cause.IP_BANNED) {
            how = "vanilla";
        } else if (net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(e.reason()).toLowerCase(Locale.ROOT).contains("ban")) {
            how = "kick-message";
        }
        if (how != null) {
            telemetry.ban(e.getPlayer().getUniqueId(), how, System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        tracks.remove(id);
        byEntity.remove(e.getPlayer().getEntityId());
        exempt.remove(id);
        names.remove(id);
        bypass.remove(id);
        alertsOff.remove(id);
        if (e.getPlayer().hasPermission("grimml.alerts")) {
            staffLeft.put(id, System.currentTimeMillis());
        }
        grim.forget(id);
        overlay.viewerQuit(id);
        overlay.forgetTarget(id);
    }


    private static final long OPPONENT_MEMORY_NANOS = 5_000_000_000L; // how long a hit keeps someone as the opponent


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

    // the item category the features look at (axe, spear, mace, cobweb, shield or 0)
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
        if (n.equals("COBWEB")) {
            return PlayerTrack.CAT_COBWEB; // only for WebRepeat, no feature uses it
        }
        return n.equals("SHIELD") ? PlayerTrack.CAT_SHIELD : 0;
    }

    // saves when a player was hurt and by who
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

    // Grim eats its transaction replies before other listeners see them, so we take them from Grim's event
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

    // true for grimml.bypass, creative, spectator and Bedrock players
    private boolean isExempt(Player p) {
        if (p.hasPermission("grimml.bypass")) {
            return true;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        // Bedrock players (Floodgate) have different input and the models never saw them
        return getConfig().getBoolean("scan.skip-bedrock", true) && p.getUniqueId().getMostSignificantBits() == 0L;
    }

    // once a second: who is exempt and which checks each player bypasses
    private void refreshExempt() {
        List<String> checks = allChecks();
        for (Player p : Bukkit.getOnlinePlayers()) {
            exempt.put(p.getUniqueId(), isExempt(p));
            Set<String> mine = new java.util.HashSet<>();
            for (String c : checks) {
                String key = c.toLowerCase(Locale.ROOT);
                if (p.hasPermission("grimml.bypass." + key)) {
                    mine.add(key);
                }
            }
            if (p.hasPermission("grimml.bypass.telemetry")) {
                mine.add("telemetry"); // never sent, still checked
            }
            if (p.hasPermission("grimml.telemetry.trusted")) {
                mine.add("trusted"); // telemetry labels this player's fights legit
            }
            bypass.put(p.getUniqueId(), mine);
            if (overlay.isOn(p.getUniqueId()) && !p.hasPermission("grimml.overlay")) {
                overlay.turnOff(p.getUniqueId());
            }
        }
    }


    private static final String P = "&bGrimML &8» ";

    private static Component mm(String text, TagResolver... r) {
        return MiniMessage.miniMessage().deserialize(text, r);
    }

    private Component prefix() {
        return GrimBridge.colour(getConfig().getString("alerts.prefix", "&bGrimML &8»") + " ");
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m" : s < 86_400 ? (s / 3600) + "h" : (s / 86_400) + "d";
    }

    private record Missed(UUID uuid, String name, String what, boolean punished, int count, int maxConf, long lastMs) {
    }

    // the alerts a staff member missed since they last left (at most history.on-join.max-minutes back), per player and check
    private void missedAlerts(Player p) {
        long now = System.currentTimeMillis();
        long since = Math.max(staffLeft.getOrDefault(p.getUniqueId(), 0L),
                now - Math.max(1, getConfig().getLong("history.on-join.max-minutes", 60)) * 60_000L);
        Map<String, Missed> grouped = new HashMap<>();
        int alerts = 0;
        for (AlertHistory.Entry e : history.recentSince(since)) {
            if (!e.punishment()) {
                alerts++;
            }
            String what = e.punishment() ? e.group() : GrimBridge.friendly(e.check());
            grouped.merge(e.uuid() + "|" + e.punishment() + "|" + what,
                    new Missed(e.uuid(), e.name(), what, e.punishment(), 1, e.confidence(), e.ms()),
                    (a, b) -> new Missed(a.uuid(), b.name(), a.what(), a.punished(), a.count() + 1,
                            Math.max(a.maxConf(), b.maxConf()), Math.max(a.lastMs(), b.lastMs())));
        }
        if (grouped.isEmpty()) {
            return;
        }
        List<Missed> list = new ArrayList<>(grouped.values());
        list.sort((a, b) -> Long.compare(b.lastMs(), a.lastMs()));
        long players = list.stream().map(Missed::uuid).distinct().count();
        p.sendMessage(prefix().append(mm("<gray>While you were away: <white><alerts></white> alerts on <white><players></white> "
                        + "player<s>.", Placeholder.unparsed("alerts", Integer.toString(alerts)),
                Placeholder.unparsed("players", Long.toString(players)), Placeholder.unparsed("s", players == 1 ? "" : "s"))));
        int max = Math.max(1, getConfig().getInt("history.on-join.max-lines", 5));
        boolean canLook = p.hasPermission("grimml.history");
        for (Missed m : list.subList(0, Math.min(max, list.size()))) {
            TagResolver r = TagResolver.resolver(Placeholder.unparsed("player", m.name()),
                    Placeholder.unparsed("what", m.what()), Placeholder.unparsed("count", Integer.toString(m.count())),
                    Placeholder.unparsed("conf", Integer.toString(m.maxConf())),
                    Placeholder.unparsed("ago", ago(now - m.lastMs())));
            Component line = m.punished()
                    ? mm("<dark_gray> - </dark_gray><white><player></white> <red>punished</red> <gray>(<what>), <ago> ago", r)
                    : mm("<dark_gray> - </dark_gray><white><player></white> <aqua><what></aqua> <gray>x<count>, up to "
                    + "<white><conf>%</white>, <ago> ago", r);
            if (canLook) {
                line = line.hoverEvent(HoverEvent.showText(mm("<gray>Click for <white><player></white>'s history", r)))
                        .clickEvent(ClickEvent.runCommand("/grimml history " + m.name()));
            }
            p.sendMessage(line);
        }
        if (list.size() > max) {
            p.sendMessage(mm("<dark_gray> - </dark_gray><gray>and <white><n></white> more"
                            + (canLook ? ", see /grimml history \\<player>" : ""),
                    Placeholder.unparsed("n", Integer.toString(list.size() - max))));
        }
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    // /grimml history <player> [days]
    // /grimml label <player> cheat|legit [kind]: sent with telemetry so the player's fights can be used for training
    private void label(CommandSender sender, String label, String[] args) {
        if (telemetry == null || !telemetry.enabled()) {
            sender.sendMessage(prefix().append(mm("<gray>Telemetry is <red>off</red>, so labels go nowhere. Turn on "
                    + "<white>telemetry.enabled</white> in config.yml first.")));
            return;
        }
        String what = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        String kind = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
        if (args.length < 3 || !Telemetry.LABELS.contains(what) || (kind != null
                && (!what.equals("cheat") || !Telemetry.KINDS.contains(kind)))) {
            sender.sendMessage(prefix().append(mm("<gray>Usage: <white>/<label> label \\<player> cheat|legit [kind]</white>",
                    Placeholder.unparsed("label", label))));
            sender.sendMessage(prefix().append(mm("<gray>Kinds for cheat: <white><kinds></white>",
                    Placeholder.unparsed("kinds", String.join(", ", Telemetry.KINDS)))));
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        org.bukkit.OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            sender.sendMessage(prefix().append(mm("<white><player></white> <gray>has never joined this server.",
                    Placeholder.unparsed("player", args[1]))));
            return;
        }
        String name = target.getName() == null ? args[1] : target.getName();
        if (bypasses(target.getUniqueId(), "telemetry")) {
            sender.sendMessage(prefix().append(mm("<white><player></white> <gray>has <white>grimml.bypass.telemetry</white>, "
                    + "so their fights are never sent.", Placeholder.unparsed("player", name))));
            return;
        }
        int fights = telemetry.label(target.getUniqueId(), what, kind, System.currentTimeMillis());
        getLogger().info(sender.getName() + " labelled " + name + " " + what + (kind == null ? "" : " (" + kind + ")"));
        String c = what.equals("cheat") ? "red" : "green";
        sender.sendMessage(prefix().append(mm("<gray>Labelled <white><player></white> <" + c + "><what></" + c + ">"
                        + " for telemetry: <white><fights></white> waiting fights and the next 30 minutes of "
                        + "their fights are sent with it.",
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("what", what + (kind == null ? "" : " (" + kind + ")")),
                Placeholder.unparsed("fights", Integer.toString(fights)))));
    }

    private void showHistory(CommandSender sender, String label, String[] args) {
        if (!history.enabled()) {
            say(sender, "&7Alert history is off in config.yml &8(history.enabled)&7.");
            return;
        }
        if (args.length < 2) {
            say(sender, "&7Usage: &f/" + label + " history <player> [days]");
            return;
        }
        int keep = Math.max(1, getConfig().getInt("history.keep-days", 14));
        int days = keep;
        if (args.length > 2) {
            try {
                days = Math.max(1, Math.min(keep, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                say(sender, "&c" + args[2] + " is not a number of days.");
                return;
            }
        }
        int d = days;
        history.search(args[1], days, 15, System.currentTimeMillis(), res -> Bukkit.getScheduler().runTask(this, () -> {
            String who = res.name() == null ? args[1] : res.name();
            TagResolver head = TagResolver.resolver(Placeholder.unparsed("player", who),
                    Placeholder.unparsed("total", Integer.toString(res.total())),
                    Placeholder.unparsed("days", Integer.toString(d)),
                    Placeholder.unparsed("shown", Integer.toString(res.entries().size())));
            if (res.total() == 0) {
                sender.sendMessage(prefix().append(mm("<white><player></white> <gray>has no alerts in the last <days> days.", head)));
                return;
            }
            sender.sendMessage(prefix().append(mm("<white><player></white><gray>: <white><total></white> in the last "
                    + "<days> days" + (res.total() > res.entries().size() ? ", newest <shown>" : "") + ".", head)));
            for (AlertHistory.Entry e : res.entries()) {
                TagResolver r = TagResolver.resolver(
                        Placeholder.unparsed("when", WHEN.format(java.time.Instant.ofEpochMilli(e.ms()))),
                        Placeholder.unparsed("check", GrimBridge.friendly(e.check())),
                        Placeholder.unparsed("conf", Integer.toString(e.confidence())),
                        Placeholder.unparsed("vl", Integer.toString(e.vl())),
                        Placeholder.unparsed("group", e.group() == null ? "" : e.group()),
                        Placeholder.unparsed("fw", e.windows() >= 0 ? e.flagged() + "/" + e.windows() + " windows, " : ""));
                sender.sendMessage(e.punishment()
                        ? mm("<dark_gray> - </dark_gray><gray><when></gray> <red>punished</red> <white><group></white> "
                        + "<gray>at <vl> violations (<check>)", r)
                        : mm("<dark_gray> - </dark_gray><gray><when></gray> <aqua><check></aqua> <white><conf>%</white> "
                        + "<gray><fw>vl <vl>", r));
            }
        }));
    }

    private static void say(CommandSender to, String text) {
        to.sendMessage(GrimBridge.colour(P + text));
    }

    private static void line(CommandSender to, String text) {
        to.sendMessage(GrimBridge.colour(text));
    }

    private static String onOff(boolean on) {
        return on ? "&aon" : "&coff";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    say(sender, "&7Console alerts are set by &falerts.console &7in config.yml.");
                } else if (!p.hasPermission("grimml.alerts")) {
                    say(sender, "&cYou don't have permission to see alerts.");
                } else if (alertsOff.remove(p.getUniqueId())) {
                    say(sender, "&7Alerts &aon&7.");
                } else {
                    alertsOff.add(p.getUniqueId());
                    say(sender, "&7Alerts &coff&7.");
                }
            }
            case "overlay" -> {
                if (!(sender instanceof Player p)) {
                    say(sender, "&7Only players can see the overlay.");
                } else if (!p.hasPermission("grimml.overlay")) {
                    say(sender, "&cYou don't have permission to see the overlay.");
                } else if (!getConfig().getBoolean("overlay.enabled", true)) {
                    say(sender, "&7The overlay is off in config.yml &8(overlay.enabled)&7.");
                } else if (overlay.toggle(p.getUniqueId())) {
                    say(sender, "&7Overlay &aon&7: flagged players show their checks above their heads.");
                } else {
                    say(sender, "&7Overlay &coff&7.");
                }
            }
            case "history" -> {
                if (!sender.hasPermission("grimml.history")) {
                    say(sender, "&cYou don't have permission to see alert history.");
                } else {
                    showHistory(sender, label, args);
                }
            }
            case "label" -> {
                if (!sender.hasPermission("grimml.label")) {
                    say(sender, "&cYou don't have permission to label players.");
                } else {
                    label(sender, label, args);
                }
            }
            case "telemetry" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (telemetry == null || !telemetry.enabled()) {
                    say(sender, "&7Telemetry is &coff&7. Turn it on with &ftelemetry.enabled: true &7in config.yml, then "
                            + "&f/" + label + " reload&7. It shares anonymous fight data to improve the checks.");
                    return true;
                }
                say(sender, "&7Telemetry &aon&7: &f" + telemetry.status().get("buffered") + " &7lines waiting, last: &f"
                        + telemetry.lastResult());
                line(sender, "&8 - &7" + telemetry.sampling());
                say(sender, "&7Sending now...");
                telemetry.sendNow(r -> Bukkit.getScheduler().runTask(this, () -> say(sender, "&7Telemetry: &f" + r)));
            }
            case "status" -> {
                if (!admin(sender)) {
                    return true;
                }
                ModelSet set = store.current();
                say(sender, "&7Models &fv" + set.version() + (set.published().isEmpty() ? "" : " &8(" + set.published() + ")"));
                for (ModuleModel m : set.models()) {
                    line(sender, String.format("&8 - &f%s &7(%s) %s &8alert at %.0f%% of a fight's windows",
                            GrimBridge.friendly(m.checkName()), m.checkName(), onOff(m.enabled()), m.alertShare() * 100));
                }
                Scanner.GridSettings g = gridSettings();
                Scanner.LungeSettings l = lungeSettings();
                boolean[] on = {g.enabled(), l.rateEnabled(), l.holdEnabled(), l.orderEnabled(), l.axeEnabled(),
                        l.stunEnabled(), l.slamEnabled(), l.webEnabled()};
                List<String> rules = new ArrayList<>(List.of(Scanner.GridSettings.CHECK));
                rules.addAll(List.of(Scanner.LungeSettings.CHECKS));
                for (int i = 0; i < rules.size(); i++) {
                    line(sender, "&8 - &f" + GrimBridge.friendly(rules.get(i)) + " &7(" + rules.get(i) + ") " + onOff(on[i]));
                }
                List<Punishments.Group> groups = grim.groups();
                line(sender, "&8 - &7Punishments " + (groups.isEmpty() ? "&coff" : "&a" + groups.size() + " groups"));
                for (Punishments.Group pg : groups) {
                    line(sender, "&8   - &f" + pg.name() + " &7at &f" + pg.at().keySet().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")) + " &7violations");
                }
                if (args.length > 1) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        say(sender, "&c" + args[1] + " is not online.");
                    } else {
                        UUID id = target.getUniqueId();
                        String st = scanner.gridStatus(id);
                        Set<String> b = bypass.getOrDefault(id, Set.of());
                        String state = Boolean.TRUE.equals(exempt.get(id))
                                ? "&enot scanned &8(grimml.bypass, creative/spectator or Bedrock)"
                                : "&ascanned" + (b.isEmpty() ? "" : " &7except " + String.join(", ", b));
                        say(sender, "&f" + target.getName() + "&7: " + state + (st == null ? "" : " &8| aim grid " + st));
                        for (Punishments.Group pg : groups) {
                            double v = grim.groupViolations(id, pg.name());
                            if (v > 0) {
                                line(sender, String.format("&8   - &f%s &7%.1f violations", pg.name(), v));
                            }
                        }
                    }
                }
                if (telemetry != null) {
                    line(sender, "&8 - &7Telemetry " + (telemetry.enabled() ? "&aon &8(" + telemetry.status().get("buffered")
                            + " lines waiting, server id " + telemetry.serverId().substring(0, 8) + ")" : "&coff"));
                    if (telemetry.enabled()) {
                        line(sender, "&8   - &7" + telemetry.sampling());
                    }
                }
                line(sender, String.format("&7Tracking &f%d &7players, &f%d &7windows scored, &f%d &7flags, last scan &f%.2f ms",
                        tracks.size(), scanner.windowsScored.get(), scanner.flagsRaised.get(), scanner.lastRunMs));
                if (!store.lastError().isEmpty()) {
                    say(sender, "&c" + store.lastError());
                }
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                reloadConfig();
                checkConfigVersion();
                grim.settings(bridgeSettings());
                history.settings(getConfig().getBoolean("history.enabled", true), getConfig().getInt("history.keep-days", 14));
                if (telemetry != null) {
                    telemetry.settings(telemetrySettings());
                }
                overlay.settings(overlaySettings());
                scanner.settings(scanSettings(), gridSettings(), lungeSettings());
                say(sender, "&7Config reloaded &8(window size and scan interval need a restart)&7.");
            }
            case "update" -> {
                if (!admin(sender)) {
                    return true;
                }
                say(sender, "&7Checking for new models...");
                updateExec.execute(() -> {
                    int before = store.current().version();
                    checkForModels();
                    int after = store.current().version();
                    String msg = after > before ? "&7Updated to models &fv" + after
                            : (store.lastError().isEmpty() ? "&7Already on the newest models &8(v" + after + ")" : "&c" + store.lastError());
                    Bukkit.getScheduler().runTask(this, () -> say(sender, msg));
                });
            }
            default -> {
                say(sender, "&fv" + getPluginMeta().getVersion() + " &7- machine-learning checks for Grim");
                if (sender.hasPermission("grimml.alerts")) {
                    line(sender, "&8 - &f/" + label + " alerts &7turn your alerts on or off");
                }
                if (sender.hasPermission("grimml.overlay")) {
                    line(sender, "&8 - &f/" + label + " overlay &7show flagged checks above players' heads");
                }
                if (sender.hasPermission("grimml.history")) {
                    line(sender, "&8 - &f/" + label + " history <player> [days] &7a player's past alerts and punishments");
                }
                if (sender.hasPermission("grimml.admin")) {
                    line(sender, "&8 - &f/" + label + " status [player] &7checks, models and a player's state");
                    line(sender, "&8 - &f/" + label + " reload &7reload config.yml");
                    line(sender, "&8 - &f/" + label + " update &7download newer models now");
                    line(sender, "&8 - &f/" + label + " telemetry &7telemetry status, and send now");
                }
                if (sender.hasPermission("grimml.label")) {
                    line(sender, "&8 - &f/" + label + " label <player> cheat|legit [kind] &7tell telemetry what a player is");
                }
            }
        }
        return true;
    }

    private static boolean admin(CommandSender sender) {
        if (sender.hasPermission("grimml.admin")) {
            return true;
        }
        say(sender, "&cYou don't have permission to do that.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>(); 
        if (args.length == 1) {
            options.add("help");
            if (sender.hasPermission("grimml.alerts")) {
                options.add("alerts");
            }
            if (sender.hasPermission("grimml.overlay")) {
                options.add("overlay");
            }
            if (sender.hasPermission("grimml.history")) {
                options.add("history");
            }
            if (sender.hasPermission("grimml.label")) {
                options.add("label");
            }
            if (sender.hasPermission("grimml.admin")) {
                options.addAll(List.of("status", "reload", "update", "telemetry"));
            }
        } else if (args.length == 2 && ((args[0].equalsIgnoreCase("status") && sender.hasPermission("grimml.admin"))
                || (args[0].equalsIgnoreCase("label") && sender.hasPermission("grimml.label"))
                || (args[0].equalsIgnoreCase("history") && sender.hasPermission("grimml.history")))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("label") && sender.hasPermission("grimml.label")) {
            options.addAll(Telemetry.LABELS);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("label") && args[2].equalsIgnoreCase("cheat")
                && sender.hasPermission("grimml.label")) {
            options.addAll(Telemetry.KINDS);
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
