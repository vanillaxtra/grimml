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

    // a full walk stats every file. with millions of recordings that is seconds to minutes of disk work, and it
    // used to run every safety-check-interval-ms (5s), i.e. more or less permanently. the writer already knows
    // every byte it adds, so walk once, then count forward, and only re-walk occasionally to correct drift
    private static final long RESCAN_MS = 30 * 60_000L;
    // while paused for disk the only way out is someone freeing space, so look a little more often
    private static final long RESCAN_PAUSED_MS = 2 * 60_000L;

    private long baseBytes = -1L;
    private long writtenAtWalk;
    private long lastWalkMs;

    private void check() {
        long cap = config.maxDiskMb();
        if (cap <= 0) {
            return;
        }
        RecordWriter writer = plugin.writer();
        if (writer == null) {
            return;
        }
        boolean diskPaused = writer.isPaused(RecordWriter.PAUSE_DISK);
        long sinceWalk = System.currentTimeMillis() - lastWalkMs;
        if (baseBytes < 0 || sinceWalk >= (diskPaused ? RESCAN_PAUSED_MS : RESCAN_MS)) {
            long writtenBefore = writer.writtenBytes();
            long size = directorySize(writer.recordingsDir());
            // a disable mid-walk leaves a partial total; do not act on it
            if (!running.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            baseBytes = size;
            writtenAtWalk = writtenBefore;
            lastWalkMs = System.currentTimeMillis();
        }
        long used = usedBytes(baseBytes, writtenAtWalk, writer.writtenBytes());
        long maxBytes = cap * 1024L * 1024L;
        if (used > maxBytes) {
            if (!diskPaused) {
                writer.setPaused(RecordWriter.PAUSE_DISK, true);
                plugin.getLogger().warning("recordings directory over " + cap + " MiB; pausing writer until disk frees");
            }
        } else if (diskPaused) {
            writer.setPaused(RecordWriter.PAUSE_DISK, false);
            plugin.getLogger().info("recordings directory back under " + cap + " MiB; resuming writer");
        }
    }

    // size at the last walk plus everything the writer has added since
    static long usedBytes(long baseBytes, long writtenAtWalk, long writtenNow) {
        return Math.max(0L, baseBytes) + Math.max(0L, writtenNow - writtenAtWalk);
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
