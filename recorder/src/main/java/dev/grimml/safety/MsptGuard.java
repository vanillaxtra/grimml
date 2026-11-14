package dev.grimml.safety;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;
import dev.grimml.writer.RecordWriter;
import org.bukkit.Bukkit;

public final class MsptGuard {

    private final GrimMLPlugin plugin;
    private final PluginConfig config;
    private long lastTickCheck;

    public MsptGuard(GrimMLPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void check() {
        if (config.msptPauseThreshold() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickCheck < 1000L) {
            return;
        }
        lastTickCheck = now;
        RecordWriter writer = plugin.writer();
        double mspt = Bukkit.getAverageTickTime();
        // only this guard's own bit: recovering from lag must not resume a disk-cap or manual pause
        if (mspt > config.msptPauseThreshold()) {
            if (!writer.isPaused(RecordWriter.PAUSE_MSPT)) {
                writer.setPaused(RecordWriter.PAUSE_MSPT, true);
                plugin.getLogger().warning("mspt " + mspt + " over threshold " + config.msptPauseThreshold() + "; pausing recorder");
            }
        } else if (mspt < config.msptPauseThreshold() * 0.8) {
            if (writer.isPaused(RecordWriter.PAUSE_MSPT)) {
                writer.setPaused(RecordWriter.PAUSE_MSPT, false);
                plugin.getLogger().info("mspt recovered; resuming recorder");
            }
        }
    }
}
