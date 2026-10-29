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
        if (mspt > config.msptPauseThreshold()) {
            if (!writer.isPaused()) {
                writer.setPaused(true);
                plugin.getLogger().warning("mspt " + mspt + " over threshold " + config.msptPauseThreshold() + "; pausing recorder");
            }
        } else if (mspt < config.msptPauseThreshold() * 0.8) {
            if (writer.isPaused()) {
                writer.setPaused(false);
                plugin.getLogger().info("mspt recovered; resuming recorder");
            }
        }
    }
}
