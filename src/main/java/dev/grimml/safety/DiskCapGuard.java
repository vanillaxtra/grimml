package dev.grimml.safety;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;
import dev.grimml.writer.RecordWriter;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiskCapGuard {

    private final GrimMLPlugin plugin;
    private final PluginConfig config;

    public DiskCapGuard(GrimMLPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void check() {
        long cap = config.maxDiskMb();
        if (cap <= 0) {
            return;
        }
        RecordWriter writer = plugin.writer();
        long maxBytes = cap * 1024L * 1024L;
        long used = directorySize(writer.recordingsDir());
        if (used <= maxBytes) {
            return;
        }
        writer.setPaused(true);
        Bukkit.getScheduler().runTask(plugin, () ->
                plugin.getLogger().warning("recordings directory over " + cap + " MiB; pausing writer until disk frees"));
    }

    private long directorySize(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.toFile().isFile()).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
