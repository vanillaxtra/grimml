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

    public void load(FileConfiguration cfg) {
        enabled = cfg.getBoolean("enabled", true);
        anonymizeSalt = cfg.getString("anonymize-salt", "change-me");
        preCombatSeconds = Math.max(1, cfg.getInt("pre-combat-seconds", 5));
        postCombatSeconds = Math.max(0, cfg.getInt("post-combat-seconds", 2));
        writerIntervalMs = Math.max(250L, cfg.getLong("writer-interval-ms", 2000L));
        writerQueueCapacity = Math.max(8, cfg.getInt("writer-queue-capacity", 256));
        ringCapacity = Math.max(32, cfg.getInt("ring-capacity", 128));
        eventRingCapacity = Math.max(16, cfg.getInt("event-ring-capacity", 64));
        msptPauseThreshold = cfg.getDouble("mspt-pause-threshold", 50.0);
        safetyCheckIntervalMs = Math.max(1000L, cfg.getLong("safety-check-interval-ms", 5000L));
        maxDiskMb = Math.max(0L, cfg.getLong("max-disk-mb", 10240L));
        recordingsDir = cfg.getString("recordings-dir", "recordings");
    }

    public boolean enabled() {
        return enabled;
    }

    public String anonymizeSalt() {
        return anonymizeSalt;
    }

    public int preCombatSeconds() {
        return preCombatSeconds;
    }

    public int postCombatSeconds() {
        return postCombatSeconds;
    }

    public long writerIntervalMs() {
        return writerIntervalMs;
    }

    public int writerQueueCapacity() {
        return writerQueueCapacity;
    }

    public int ringCapacity() {
        return ringCapacity;
    }

    public int eventRingCapacity() {
        return eventRingCapacity;
    }

    public double msptPauseThreshold() {
        return msptPauseThreshold;
    }

    public long safetyCheckIntervalMs() {
        return safetyCheckIntervalMs;
    }

    public long maxDiskMb() {
        return maxDiskMb;
    }

    public String recordingsDir() {
        return recordingsDir;
    }
}
