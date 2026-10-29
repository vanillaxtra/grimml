package dev.grimml.config;

import org.bukkit.configuration.file.FileConfiguration;

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
    }

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
}
