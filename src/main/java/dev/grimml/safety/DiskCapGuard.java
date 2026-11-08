package dev.grimml.safety;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;
import dev.grimml.storage.Offloader;
import dev.grimml.writer.RecordWriter;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

// walks the recordings directory on grimml-disk so the stat loop never sits on the server thread
public final class DiskCapGuard {

    private final GrimMLPlugin plugin;
    private final PluginConfig config;
    private static final long OFFLOAD_INTERVAL_MS = 10 * 60 * 1000L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private volatile boolean pausedByDisk;
    private long lastOffloadMs;

    public DiskCapGuard(GrimMLPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (worker != null) {
            return;
        }
        running.set(true);
        worker = new Thread(this::loop, "grimml-disk");
        worker.setDaemon(true);
        // yield to the server thread while a large tree is being walked
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    public void stop() {
        running.set(false);
        Thread thread = worker;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (!sleepInterval()) {
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                check();
            } catch (Exception e) {
                plugin.getLogger().warning("disk cap check failed: " + e.getMessage());
            }
        }
    }

    private boolean sleepInterval() {
        try {
            Thread.sleep(config.safetyCheckIntervalMs());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void check() {
        RecordWriter writer = plugin.writer();
        if (writer == null) {
            return;
        }
        offloadIfDue(writer);
        long cap = config.maxDiskMb();
        if (cap <= 0) {
            return;
        }
        long used = directorySize(writer.recordingsDir());
        // a disable mid-walk leaves a partial total; do not pause from it
        if (!running.get() || Thread.currentThread().isInterrupted()) {
            return;
        }
        long maxBytes = cap * 1024L * 1024L;
        if (used <= maxBytes * 9 / 10 && pausedByDisk && writer.isPaused()) {
            pausedByDisk = false;
            writer.setPaused(false);
            plugin.getLogger().info("recordings directory back under cap; resuming writer");
            return;
        }
        if (used <= maxBytes || writer.isPaused()) {
            return;
        }
        pausedByDisk = true;
        writer.setPaused(true);
        plugin.getLogger().warning("recordings directory over " + cap + " MiB; pausing writer until disk frees");
    }

    private void offloadIfDue(RecordWriter writer) {
        if (!config.offloadEnabled() || "none".equals(config.rotation())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOffloadMs < OFFLOAD_INTERVAL_MS) {
            return;
        }
        lastOffloadMs = now;
        String dir = config.offloadDir();
        if (dir == null || dir.isBlank()) {
            plugin.getLogger().warning("offload.enabled is true but offload.dir is empty; skipping");
            return;
        }
        Path target = plugin.getDataFolder().toPath().resolve(dir).toAbsolutePath();
        new Offloader(writer.recordingsDir(), target, config.offloadAfterDays(), plugin.getLogger()).run(LocalDate.now());
    }

    // one lstat per file: visitFile already carries the size, so do not stat again
    static long directorySize(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE;
                    }
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return total[0];
        }
        return total[0];
    }
}
