package dev.grimml;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.capture.PacketListener;
import dev.grimml.clock.TickClock;
import dev.grimml.commands.GrimMLCommand;
import dev.grimml.config.PluginConfig;
import dev.grimml.fight.FightAssembler;
import dev.grimml.geyser.GeyserHook;
import dev.grimml.grim.GrimCheckCatalog;
import dev.grimml.grim.GrimFlagHook;
import dev.grimml.hooks.LiteBansHook;
import dev.grimml.listeners.DamageListener;
import dev.grimml.listeners.ValidityListener;
import dev.grimml.listeners.VanillaBanListener;
import dev.grimml.roster.BanLabelService;
import dev.grimml.roster.RosterStore;
import dev.grimml.safety.DiskCapGuard;
import dev.grimml.safety.MsptGuard;
import dev.grimml.state.SessionLifecycle;
import dev.grimml.state.SprintSneakTracker;
import dev.grimml.state.StateSampler;
import dev.grimml.format.RecordTypes;
import dev.grimml.mark.MarkTracker;
import dev.grimml.mark.MarkerWriter;
import dev.grimml.mark.TestAccounts;
import dev.grimml.taxonomy.Taxonomy;
import dev.grimml.taxonomy.TaxonomyLoader;
import dev.grimml.writer.RecordWriter;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class GrimMLPlugin extends JavaPlugin {

    private final PluginConfig config = new PluginConfig();
    private final TickClock clock = new TickClock();
    private SessionRegistry registry;
    private RecordWriter writer;
    private PacketListener packetListener;
    private StateSampler stateSampler;
    private DiskCapGuard diskCap;
    private GrimFlagHook grimHook;
    private GrimCheckCatalog grimChecks;
    private GeyserHook geyserHook;
    private org.bukkit.scheduler.BukkitTask grimFlushTask;
    private volatile Taxonomy taxonomy = Taxonomy.empty();
    private final MarkTracker marks = new MarkTracker();
    private TestAccounts testAccounts;
    private volatile boolean recordingEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("taxonomy.yml", false);
        config.load(getConfig());
        testAccounts = new TestAccounts(getDataFolder().toPath().resolve("test-accounts.txt"));
        testAccounts.load();
        reloadTaxonomy();

        recordingEnabled = config.enabled();
        registry = new SessionRegistry(config, clock);
        try {
            writer = new RecordWriter(this, config);
        } catch (IOException e) {
            getLogger().severe("cannot create recordings directory: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        writer.setVersions(
                Bukkit.getMinecraftVersion(),
                RecordWriter.pluginVersion("GrimAC"),
                RecordWriter.pluginVersion("packetevents")
        );
        writer.setAssembler(new FightAssembler(registry, config, clock, writer));
        stampTaxonomy();

        diskCap = new DiskCapGuard(this, config);
        diskCap.start();

        geyserHook = new GeyserHook();
        geyserHook.init();

        packetListener = new PacketListener(this, clock);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        getServer().getPluginManager().registerEvents(new SessionLifecycle(this, registry, geyserHook), this);
        getServer().getPluginManager().registerEvents(new SprintSneakTracker(registry), this);
        getServer().getPluginManager().registerEvents(new DamageListener(this, clock), this);
        getServer().getPluginManager().registerEvents(new ValidityListener(this), this);
        stateSampler = new StateSampler(this, registry, config, geyserHook, new MsptGuard(this, config), clock);
        stateSampler.start();

        grimChecks = new GrimCheckCatalog(getDataFolder().toPath().resolve("grim-checks.json"));
        grimChecks.load();
        seedTaxonomyChecks();
        grimHook = new GrimFlagHook(this, clock, grimChecks);
        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            grimHook.enable();
        }
        grimFlushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, grimChecks::flush, 100L, 100L);

        BanLabelService bans = new BanLabelService(this, new RosterStore(getDataFolder().toPath().resolve(config.rosterFile())));
        getServer().getPluginManager().registerEvents(new VanillaBanListener(bans), this);
        new LiteBansHook(this, bans).enable();

        GrimMLCommand cmd = new GrimMLCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("grimml", "grimml recorder admin", cmd));

        getLogger().info("grimml recorder enabled schema 3");
    }

    @Override
    public void onDisable() {
        recordingEnabled = false;
        closeOpenMarks(true);
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (stateSampler != null) {
            stateSampler.stop();
        }
        if (grimFlushTask != null) {
            grimFlushTask.cancel();
        }
        if (grimChecks != null) {
            grimChecks.flush();
        }
        if (diskCap != null) {
            diskCap.stop();
        }
        if (writer != null) {
            writer.stop();
        }
        if (registry != null) {
            registry.clear();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public TickClock clock() {
        return clock;
    }

    public RecordWriter writer() {
        return writer;
    }

    public SessionRegistry registry() {
        return registry;
    }

    public boolean recordingEnabled() {
        return recordingEnabled && writer != null && !writer.isPaused();
    }

    public Taxonomy taxonomy() {
        return taxonomy;
    }

    public GrimCheckCatalog grimChecks() {
        return grimChecks;
    }

    public GrimFlagHook grimHook() {
        return grimHook;
    }

    public MarkTracker marks() {
        return marks;
    }

    public TestAccounts testAccounts() {
        return testAccounts;
    }

    public void closeOpen(java.util.UUID uuid, PlayerRingBuffer buf, boolean interrupted) {
        if (writer == null || buf == null) {
            return;
        }
        var prev = marks.end(uuid);
        if (prev == null) {
            return;
        }
        long post = config.postCombatSeconds() * 1_000_000_000L;
        MarkerWriter.write(buf, clock, writer, post, RecordTypes.MARKER_END,
                prev.labelId, prev.clientId, prev.moduleId, java.util.List.of(prev.kv), interrupted);
    }

    public void closeOpenMarks(boolean interrupted) {
        if (writer == null || registry == null) {
            return;
        }
        long post = config.postCombatSeconds() * 1_000_000_000L;
        for (var open : marks.drainAll()) {
            var buf = registry.get(open.uuid);
            if (buf == null) {
                continue;
            }
            MarkerWriter.write(buf, clock, writer, post, RecordTypes.MARKER_END,
                    open.labelId, open.clientId, open.moduleId, java.util.List.of(open.kv), interrupted);
        }
    }

    private void seedTaxonomyChecks() {
        if (grimChecks == null || taxonomy == null) {
            return;
        }
        for (var m : taxonomy.modules.values()) {
            for (String n : m.expectedGrim) {
                grimChecks.learn(n);
            }
        }
    }

    private void stampTaxonomy() {
        if (writer == null || taxonomy == null) {
            return;
        }
        writer.enqueueSessionLine("taxonomy " + taxonomy.version + " " + Long.toUnsignedString(taxonomy.hash, 36));
    }

    public void reloadTaxonomy() {
        try {
            taxonomy = TaxonomyLoader.load(getDataFolder().toPath().resolve("taxonomy.yml"));
            stampTaxonomy();
            seedTaxonomyChecks();
            getLogger().info("taxonomy v" + taxonomy.version + " modules " + taxonomy.modules.size());
        } catch (Exception e) {
            getLogger().warning("taxonomy load failed: " + e.getMessage());
            if (taxonomy == null) {
                taxonomy = Taxonomy.empty();
            }
        }
    }
}
