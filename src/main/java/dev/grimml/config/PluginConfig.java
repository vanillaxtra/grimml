package dev.grimml.config;

import dev.grimml.roster.CombatReasonMatcher;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class PluginConfig {

    private boolean enabled;
    private String anonymizeSalt;
    private int preCombatSeconds;
    private int postCombatSeconds;
    private long writerIntervalMs;
    private int writerQueueCapacity;
    private int ringCapacity;
    private int eventRingCapacity;
    private double msptPauseThreshold;
    private long safetyCheckIntervalMs;
    private long maxDiskMb;
    private String recordingsDir;
    private String serverId;
    private String kit;
    private String mapId;
    private String positionMode;
    private int bystanderRange;
    private int bystanderMax;
    private int staggerPerTick;
    private boolean banLabelsEnabled;
    private int banBackfillDays;
    private String rosterFile;
    private CombatReasonMatcher combatReasons = new CombatReasonMatcher(List.of());
    private volatile boolean markOnly;
    private boolean grimEvidenceEnabled;
    private int grimEvidenceQueueCap;
    private String grimLabelsFile;
    private String grimReviewFile;
    private String rotation;
    private boolean offloadEnabled;
    private String offloadDir;
    private int offloadAfterDays;
    private boolean mapSnapshotEnabled;
    private int mapSnapshotRadius;
    private int mapSnapshotHeight;
    private String mapSnapshotDir;
    private boolean inferenceEnabled;
    private String inferenceModelDir;
    private int inferenceQueueCapacity;
    private double inferenceDecay;
    private double inferenceAlertScore;
    private int inferenceAlertCooldownSeconds;
    private int inferenceMinFrames;

    private static final List<String> DEFAULT_COMBAT_REASONS = List.of(
            "killaura", "kill aura", "aura", "aimassist", "aim assist", "aimbot",
            "reach", "hitbox", "autoclick", "auto click", "triggerbot",
            "crystal", "totem", "retotem", "shield", "autoblock", "scaffold",
            "mace", "spear", "stun", "jump reset", "velocity", "antikb", "anti kb"
    );

    public void load(FileConfiguration cfg) {
        enabled = cfg.getBoolean("enabled", true);
        anonymizeSalt = cfg.getString("anonymize-salt", "change-me");
        preCombatSeconds = Math.max(1, cfg.getInt("pre-combat-seconds", 5));
        postCombatSeconds = Math.max(0, cfg.getInt("post-combat-seconds", 2));
        writerIntervalMs = Math.max(250L, cfg.getLong("writer-interval-ms", 2000L));
        writerQueueCapacity = Math.max(8, cfg.getInt("writer-queue-capacity", 256));
        ringCapacity = Math.max(32, cfg.getInt("ring-capacity", 160));
        eventRingCapacity = Math.max(16, cfg.getInt("event-ring-capacity", 128));
        msptPauseThreshold = cfg.getDouble("mspt-pause-threshold", 50.0);
        safetyCheckIntervalMs = Math.max(1000L, cfg.getLong("safety-check-interval-ms", 5000L));
        maxDiskMb = Math.max(0L, cfg.getLong("max-disk-mb", 20480L));
        recordingsDir = cfg.getString("recordings-dir", "recordings");
        serverId = cfg.getString("server-id", "default");
        kit = cfg.getString("mode-kit", "unknown");
        mapId = cfg.getString("map-id", "unknown");
        positionMode = cfg.getString("position-mode", "absolute");
        bystanderRange = Math.max(1, cfg.getInt("bystander-range", 16));
        bystanderMax = Math.max(0, cfg.getInt("bystander-max", 4));
        staggerPerTick = Math.max(1, cfg.getInt("stagger-players-per-tick", 8));
        banLabelsEnabled = cfg.getBoolean("ban-labels.enabled", true);
        banBackfillDays = Math.max(1, cfg.getInt("ban-labels.backfill-days", 14));
        rosterFile = cfg.getString("ban-labels.roster-file", "roster.csv");
        List<String> keys = cfg.getStringList("ban-labels.combat-reason-keywords");
        combatReasons = new CombatReasonMatcher(keys.isEmpty() ? DEFAULT_COMBAT_REASONS : keys);
        markOnly = "mark-only".equalsIgnoreCase(cfg.getString("recording-mode", "auto"));
        grimEvidenceEnabled = cfg.getBoolean("grim-evidence.enabled", true);
        grimEvidenceQueueCap = Math.max(32, cfg.getInt("grim-evidence.queue-capacity", 1024));
        grimLabelsFile = cfg.getString("grim-evidence.labels-file", "grim-labels.csv");
        grimReviewFile = cfg.getString("grim-evidence.review-file", "grim-review.csv");
        String rot = cfg.getString("rotation", "hourly").toLowerCase(java.util.Locale.ROOT);
        rotation = rot.equals("daily") || rot.equals("none") ? rot : "hourly";
        offloadEnabled = cfg.getBoolean("offload.enabled", false);
        offloadDir = cfg.getString("offload.dir", "");
        offloadAfterDays = Math.max(1, cfg.getInt("offload.after-days", 1));
        mapSnapshotEnabled = cfg.getBoolean("map-snapshot.enabled", true);
        mapSnapshotRadius = Math.max(8, Math.min(256, cfg.getInt("map-snapshot.radius", 48)));
        mapSnapshotHeight = Math.max(4, Math.min(192, cfg.getInt("map-snapshot.height", 24)));
        mapSnapshotDir = cfg.getString("map-snapshot.dir", "maps");
        inferenceEnabled = cfg.getBoolean("inference.enabled", false);
        inferenceModelDir = cfg.getString("inference.model-dir", "model");
        inferenceQueueCapacity = Math.max(8, cfg.getInt("inference.queue-capacity", 256));
        inferenceDecay = Math.max(0.0, Math.min(1.0, cfg.getDouble("inference.decay", 0.9)));
        inferenceAlertScore = Math.max(0.1, cfg.getDouble("inference.alert-score", 1.5));
        inferenceAlertCooldownSeconds = Math.max(0, cfg.getInt("inference.alert-cooldown-seconds", 60));
        inferenceMinFrames = Math.max(1, cfg.getInt("inference.min-frames", 20));
    }

    public void setMarkOnly(boolean markOnly) { this.markOnly = markOnly; }
    public String rotation() { return rotation; }
    public boolean offloadEnabled() { return offloadEnabled; }
    public String offloadDir() { return offloadDir; }
    public int offloadAfterDays() { return offloadAfterDays; }
    public boolean mapSnapshotEnabled() { return mapSnapshotEnabled; }
    public int mapSnapshotRadius() { return mapSnapshotRadius; }
    public int mapSnapshotHeight() { return mapSnapshotHeight; }
    public String mapSnapshotDir() { return mapSnapshotDir; }
    public boolean inferenceEnabled() { return inferenceEnabled; }
    public String inferenceModelDir() { return inferenceModelDir; }
    public int inferenceQueueCapacity() { return inferenceQueueCapacity; }
    public double inferenceDecay() { return inferenceDecay; }
    public double inferenceAlertScore() { return inferenceAlertScore; }
    public int inferenceAlertCooldownSeconds() { return inferenceAlertCooldownSeconds; }
    public int inferenceMinFrames() { return inferenceMinFrames; }

    public boolean enabled() { return enabled; }
    public String anonymizeSalt() { return anonymizeSalt; }
    public int preCombatSeconds() { return preCombatSeconds; }
    public int postCombatSeconds() { return postCombatSeconds; }
    public long writerIntervalMs() { return writerIntervalMs; }
    public int writerQueueCapacity() { return writerQueueCapacity; }
    public int ringCapacity() { return ringCapacity; }
    public int eventRingCapacity() { return eventRingCapacity; }
    public double msptPauseThreshold() { return msptPauseThreshold; }
    public long safetyCheckIntervalMs() { return safetyCheckIntervalMs; }
    public long maxDiskMb() { return maxDiskMb; }
    public String recordingsDir() { return recordingsDir; }
    public String serverId() { return serverId; }
    public String kit() { return kit; }
    public String mapId() { return mapId; }
    public boolean relativePositions() { return "relative".equalsIgnoreCase(positionMode); }
    public int bystanderRange() { return bystanderRange; }
    public int bystanderMax() { return bystanderMax; }
    public int staggerPerTick() { return staggerPerTick; }
    public boolean banLabelsEnabled() { return banLabelsEnabled; }
    public int banBackfillDays() { return banBackfillDays; }
    public String rosterFile() { return rosterFile; }
    public CombatReasonMatcher combatReasons() { return combatReasons; }
    public boolean markOnly() { return markOnly; }
    public boolean grimEvidenceEnabled() { return grimEvidenceEnabled; }
    public int grimEvidenceQueueCap() { return grimEvidenceQueueCap; }
    public String grimLabelsFile() { return grimLabelsFile; }
    public String grimReviewFile() { return grimReviewFile; }
}
