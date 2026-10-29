package dev.grimml.safety;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;
import dev.grimml.writer.RecordWriter;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

// walks the recordings directory on grimml-disk so the stat loop never sits on the server thread
public final class DiskCapGuard {

    private final GrimMLPlugin plugin;
    private final PluginConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

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
        long cap = config.maxDiskMb();
        if (cap <= 0) {
            return;
        }
        RecordWriter writer = plugin.writer();
        if (writer == null) {
            return;
        }
        long used = directorySize(writer.recordingsDir());
        // a disable mid-walk leaves a partial total; do not pause from it
        if (!running.get() || Thread.currentThread().isInterrupted()) {
            return;
        }
        long maxBytes = cap * 1024L * 1024L;
        if (used <= maxBytes || writer.isPaused()) {
            return;
        }
        writer.setPaused(true);
        plugin.getLogger().warning("recordings directory over " + cap + " MiB; pausing writer until disk frees");
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
