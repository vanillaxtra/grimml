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
import dev.grimml.grim.EvidenceStore;
import dev.grimml.grim.FlagQueue;
import dev.grimml.grim.GrimCheckCatalog;
import dev.grimml.grim.GrimEvidenceTracker;
import dev.grimml.grim.GrimFlagHook;
import dev.grimml.grim.GrimWeights;
import dev.grimml.grim.GrimWeightsLoader;
import dev.grimml.hooks.LiteBansBridge;
import dev.grimml.inference.AlertNotifier;
import dev.grimml.inference.InferenceService;
import dev.grimml.inference.ViolationBuffer;
import dev.grimml.listeners.DamageListener;
import dev.grimml.listeners.ProjectileListener;
import dev.grimml.listeners.ValidityListener;
import dev.grimml.listeners.VanillaBanListener;
import dev.grimml.maps.MapSnapshotter;
import dev.grimml.roster.BanLabelService;
import dev.grimml.roster.RosterStore;
import dev.grimml.safety.DiskCapGuard;
import dev.grimml.safety.MsptGuard;
import dev.grimml.scheduler.Tasks;
import dev.grimml.state.SessionLifecycle;
import dev.grimml.state.SprintSneakTracker;
import dev.grimml.state.StateSampler;
import dev.grimml.format.RecordTypes;
import dev.grimml.mark.MarkFeedback;
import dev.grimml.mark.MarkLog;
import dev.grimml.mark.MarkTracker;
import dev.grimml.mark.MarkerWriter;
import dev.grimml.mark.TestAccounts;
import dev.grimml.taxonomy.Labels;
import dev.grimml.taxonomy.Taxonomy;
import dev.grimml.taxonomy.TaxonomyLoader;
import dev.grimml.writer.RecordWriter;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
    private GrimEvidenceTracker evidence;
    private volatile GrimWeights grimWeights = GrimWeights.empty();
    private GeyserHook geyserHook;
    private ScheduledTask grimFlushTask;
    private ScheduledTask feedbackTask;
    private final Tasks tasks = new Tasks(this);
    private MapSnapshotter maps;
    private volatile InferenceService inference;
    private volatile Taxonomy taxonomy = Taxonomy.empty();
    private final MarkTracker marks = new MarkTracker();
    private MarkLog markLog;
    private TestAccounts testAccounts;
    private volatile boolean recordingEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("taxonomy.yml", false);
        saveResource("grim-weights.yml", false);
        config.load(getConfig());
        testAccounts = new TestAccounts(getDataFolder().toPath().resolve("test-accounts.txt"));
        testAccounts.load();
        markLog = new MarkLog(getDataFolder().toPath().resolve("marks.csv"));
        reloadTaxonomy();
        reloadWeights();

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

        maps = new MapSnapshotter(this, getDataFolder().toPath().resolve(config.mapSnapshotDir()));

        packetListener = new PacketListener(this, clock);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        getServer().getPluginManager().registerEvents(new SessionLifecycle(this, registry, geyserHook), this);
        getServer().getPluginManager().registerEvents(new SprintSneakTracker(registry), this);
        getServer().getPluginManager().registerEvents(new DamageListener(this, clock), this);
        getServer().getPluginManager().registerEvents(new ProjectileListener(this, clock), this);
        getServer().getPluginManager().registerEvents(new ValidityListener(this), this);
        stateSampler = new StateSampler(this, registry, config, geyserHook, new MsptGuard(this, config), clock);
        stateSampler.start();

        grimChecks = new GrimCheckCatalog(getDataFolder().toPath().resolve("grim-checks.json"));
        grimChecks.load();
        seedTaxonomyChecks();
        FlagQueue flagQueue = new FlagQueue(config.grimEvidenceQueueCap());
        evidence = new GrimEvidenceTracker(
                flagQueue, clock, registry, grimChecks,
                new EvidenceStore(getDataFolder().toPath().resolve(config.grimLabelsFile()),
                        getDataFolder().toPath().resolve(config.grimReviewFile())),
                writer, grimWeights, config.grimEvidenceEnabled());
        evidence.start();
        grimHook = new GrimFlagHook(this, clock, grimChecks, flagQueue);
        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            grimHook.enable();
        }
        grimFlushTask = tasks.repeatAsync(grimChecks::flush, 5000L, 5000L);

        reloadInference();

        BanLabelService bans = new BanLabelService(this, new RosterStore(getDataFolder().toPath().resolve(config.rosterFile())));
        getServer().getPluginManager().registerEvents(new VanillaBanListener(bans), this);
        LiteBansBridge.enableIfPresent(this, bans);

        GrimMLCommand cmd = new GrimMLCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("grimml", "grimml recorder admin", cmd));

        // en passant is forced dont @ me
        feedbackTask = tasks.repeatGlobal(new MarkFeedback(this), 4L, 4L);

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
        if (evidence != null) {
            evidence.stop();
        }
        if (inference != null) {
            inference.stop();
            inference = null;
        }
        if (feedbackTask != null) {
            feedbackTask.cancel();
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

    public Tasks tasks() {
        return tasks;
    }

    public MapSnapshotter maps() {
        return maps;
    }

    public InferenceService inference() {
        return inference;
    }

    // new service on every reload so decay and alert settings apply; old one is stopped
    public void reloadInference() {
        InferenceService old = inference;
        inference = null;
        if (old != null) {
            old.stop();
        }
        if (!config.inferenceEnabled()) {
            return;
        }
        InferenceService next = new InferenceService(config.inferenceQueueCapacity(),
                new ViolationBuffer(config.inferenceDecay(), config.inferenceAlertScore(), config.inferenceAlertCooldownSeconds()),
                new AlertNotifier(this), config.inferenceMinFrames(), getLogger());
        next.load(getDataFolder().toPath().resolve(config.inferenceModelDir()));
        inference = next;
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

    public GrimEvidenceTracker evidence() {
        return evidence;
    }

    public EvidenceStore evidenceStore() {
        return evidence == null ? null : evidence.store();
    }

    public GrimWeights grimWeights() {
        return grimWeights;
    }

    public MarkTracker marks() {
        return marks;
    }

    public MarkLog markLog() {
        return markLog;
    }

    public TestAccounts testAccounts() {
        return testAccounts;
    }

    public MarkTracker.Open closeOpen(java.util.UUID uuid, PlayerRingBuffer buf, boolean interrupted) {
        if (writer == null || buf == null) {
            return null;
        }
        var prev = marks.end(uuid);
        if (prev == null) {
            return null;
        }
        writeClosedMark(prev, buf, interrupted);
        return prev;
    }

    public void closeOpenMarks(boolean interrupted) {
        if (writer == null || registry == null) {
            return;
        }
        for (var open : marks.drainAll()) {
            var buf = registry.get(open.uuid);
            if (buf == null) {
                continue;
            }
            writeClosedMark(open, buf, interrupted);
        }
    }

    public void writeClosedMark(MarkTracker.Open prev, PlayerRingBuffer buf, boolean interrupted) {
        long post = config.postCombatSeconds() * 1_000_000_000L;
        MarkerWriter.write(buf, clock, writer, post, RecordTypes.MARKER_END,
                prev.labelId, prev.clientId, prev.moduleId, java.util.List.of(prev.kv), interrupted, prev.id);
        buf.markActive = false;
        buf.markId = "";
        logMark(prev, buf, interrupted ? "interrupted" : "end", interrupted);
    }

    public void logMark(MarkTracker.Open o, PlayerRingBuffer buf, String event, boolean interrupted) {
        if (markLog == null || o == null) {
            return;
        }
        int elapsed = (int) Math.max(0L, (System.nanoTime() - o.startNanos) / 1_000_000_000L);
        if ("start".equals(event)) {
            elapsed = 0;
        }
        String client = MarkFeedback.clientNameOf(o.kv);
        if (client.isEmpty()) {
            client = taxonomy.clientName(o.clientId);
        }
        String module = taxonomy.moduleName(o.moduleId);
        String anon = buf == null ? "" : Long.toUnsignedString(buf.anonPlayerId, 36);
        try {
            markLog.append(o.id, event, System.currentTimeMillis(), elapsed,
                    o.operator, o.account, anon, Labels.name(o.labelId), client, module, interrupted, o.kv);
        } catch (IOException e) {
            getLogger().warning("failed to write marks.csv: " + e.getMessage());
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

    public void reloadWeights() {
        try {
            grimWeights = GrimWeightsLoader.load(getDataFolder().toPath().resolve("grim-weights.yml"));
            if (evidence != null) {
                evidence.setWeights(grimWeights);
                evidence.setEnabled(config.grimEvidenceEnabled());
            }
            getLogger().info("grim-weights v" + grimWeights.version + " checks " + grimWeights.checks.size());
        } catch (Exception e) {
            getLogger().warning("grim-weights load failed: " + e.getMessage());
            if (grimWeights == null) {
                grimWeights = GrimWeights.empty();
            }
        }
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
