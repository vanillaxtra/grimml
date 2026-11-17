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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
public final class GrimMLDetector extends JavaPlugin implements Listener, TabCompleter {

    private static final int ATTACK_CAP = 2048; // per-player event ring (schema 4 events, ~60-80/s in a fight)

    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerTrack> byEntity = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> exempt = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Checks each player bypasses through grimml.bypass.<check> (refreshed once a second on the main thread). */
    private final Map<UUID, Set<String>> bypass = new ConcurrentHashMap<>();
    /** Players with grimml.alerts who turned alerts off with /grimml alerts (until they rejoin or turn them on). */
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();
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
        grim = new GrimBridge(this, bridgeSettings(),
                p -> p.hasPermission("grimml.alerts") && !alertsOff.contains(p.getUniqueId()));
        scanner = new Scanner(tracks, exempt, store::current, (id, check, description, confidence, verbose) -> {
            Set<String> b = bypass.get(id);
            if (b == null || !b.contains(check.toLowerCase(Locale.ROOT))) {
                grim.flag(id, names.getOrDefault(id, id.toString()), check, description, confidence, verbose);
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
        if (grim != null) {
            grim.shutdown();
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
                Bukkit.getScheduler().runTask(this, () -> {
                    getLogger().info("Now detecting with models v" + set.version());
                    notifyStaff("&7Now detecting with models &fv" + set.version() + "&7.");
                }));
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
                c.getBoolean("lunge-hold.enabled", true), c.getInt("lunge-hold.count", 5), c.getInt("lunge-hold.of", 10),
                c.getBoolean("swap-order.enabled", true), c.getInt("swap-order.count", 2),
                c.getDouble("swap-order.window-ms", 60_000.0),
                c.getBoolean("axe-hold.enabled", true), c.getInt("axe-hold.count", 6), c.getInt("axe-hold.of", 10),
                c.getLong("lunge-rate.alert-cooldown-ms", 15_000L));
    }

    private Scanner.Settings scanSettings() {
        return new Scanner.Settings(frameCap,
                getConfig().getInt("scan.min-attacks", 2),
                getConfig().getInt("scan.min-frames", 40),
                getConfig().getLong("scan.fight-gap-ms", 20_000L));
    }

    private GrimBridge.Settings bridgeSettings() {
        var c = getConfig();
        Map<Integer, List<String>> punish = new HashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("punishments");
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
                punish, webhook);
    }

    /** Staff with grimml.notify see model updates. */
    private void notifyStaff(String text) {
        var msg = GrimBridge.colour(getConfig().getString("alerts.prefix", "&bGrimML &8»") + " " + text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("grimml.notify")) {
                p.sendMessage(msg);
            }
        }
    }

    /** Every check name this server can flag: the loaded models plus the rule checks. */
    private List<String> allChecks() {
        List<String> out = new ArrayList<>();
        for (ModuleModel m : store.current().models()) {
            out.add(m.checkName());
        }
        out.add(Scanner.GridSettings.CHECK);
        out.addAll(List.of(Scanner.LungeSettings.CHECKS));
        return out;
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
        bypass.remove(id);
        alertsOff.remove(id);
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
        if (p.hasPermission("grimml.bypass")) {
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
            bypass.put(p.getUniqueId(), mine);
        }
    }

    // ---- /grimml

    private static final String P = "&bGrimML &8» ";

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
                boolean[] on = {g.enabled(), l.rateEnabled(), l.holdEnabled(), l.orderEnabled(), l.axeEnabled()};
                List<String> rules = new ArrayList<>(List.of(Scanner.GridSettings.CHECK));
                rules.addAll(List.of(Scanner.LungeSettings.CHECKS));
                for (int i = 0; i < rules.size(); i++) {
                    line(sender, "&8 - &f" + GrimBridge.friendly(rules.get(i)) + " &7(" + rules.get(i) + ") " + onOff(on[i]));
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
                grim.settings(bridgeSettings());
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
                if (sender.hasPermission("grimml.admin")) {
                    line(sender, "&8 - &f/" + label + " status [player] &7checks, models and a player's state");
                    line(sender, "&8 - &f/" + label + " reload &7reload config.yml");
                    line(sender, "&8 - &f/" + label + " update &7download newer models now");
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
            if (sender.hasPermission("grimml.admin")) {
                options.addAll(List.of("status", "reload", "update"));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("status") && sender.hasPermission("grimml.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
