package dev.grimml.record;

import dev.grimml.GrimMLPlugin;
import dev.grimml.configs.PluginConfig;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// single async writer thread drains snapshots to compressed files
// bounded queue drops newest samples if writer falls behind
public final class RecordWriter {

    private final GrimMLPlugin plugin;
    private final PluginConfig config;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "grimml-writer");
        t.setDaemon(true);
        return t;
    });
    private final ArrayBlockingQueue<CombatSnapshot> queue;
    private final CombatSnapshot[] scratchPool;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedWindows = new AtomicLong(0);
    private final AtomicLong writtenWindows = new AtomicLong(0);
    private final AtomicLong writtenBytes = new AtomicLong(0);
    private final Path recordingsDir;
    private final String datePrefix;
    private int scratchIndex;

    public RecordWriter(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.writerQueueCapacity());
        this.recordingsDir = plugin.getDataFolder().toPath().resolve(config.recordingsDir()).toAbsolutePath();
        Files.createDirectories(recordingsDir);
        this.datePrefix = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        int snapshotCapacity = Math.max(4, Integer.highestOneBit(config.writerQueueCapacity()) / 2);
        this.scratchPool = new CombatSnapshot[snapshotCapacity];
        for (int i = 0; i < scratchPool.length; i++) {
            scratchPool[i] = new CombatSnapshot(config.ringCapacity(), config.eventRingCapacity());
        }
        scheduler.scheduleAtFixedRate(this::drain, config.writerIntervalMs(), config.writerIntervalMs(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running.set(false);
        scheduler.shutdown();
        drain();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    public CombatSnapshot borrowSnapshot() {
        CombatSnapshot[] pool = scratchPool;
        synchronized (pool) {
            if (scratchIndex < pool.length) {
                return pool[scratchIndex++];
            }
        }
        return new CombatSnapshot(config.ringCapacity(), config.eventRingCapacity());
    }

    public void returnSnapshot(CombatSnapshot snap) {
        snap.reset();
        CombatSnapshot[] pool = scratchPool;
        synchronized (pool) {
            if (scratchIndex > 0) {
                pool[--scratchIndex] = snap;
            }
        }
    }

    // called once per sample period for each online player; snapshots only if combat is active
    public void offerSnapshotIfCombat(PlayerRingBuffer buf) {
        CombatSnapshot snap = borrowSnapshot();
        long now = System.nanoTime();
        long preNanos = config.preCombatSeconds() * 1_000_000_000L;
        if (!buf.trySnapshot(snap, now, preNanos)) {
            returnSnapshot(snap);
            return;
        }
        if (!queue.offer(snap)) {
            droppedWindows.incrementAndGet();
            returnSnapshot(snap);
        }
    }

    public void setPaused(boolean pause) {
        paused.set(pause);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public long droppedWindows() {
        return droppedWindows.get();
    }

    public long writtenWindows() {
        return writtenWindows.get();
    }

    public long writtenBytes() {
        return writtenBytes.get();
    }

    private void drain() {
        CombatSnapshot snap;
        while ((snap = queue.poll()) != null) {
            if (paused.get()) {
                returnSnapshot(snap);
                continue;
            }
            Path file = null;
            try {
                file = newFilePath(snap.anonPlayerId, snap.sessionId);
                byte[] data = BinaryFormat.encodeWindow(snap);
                ByteBuffer buf = ByteBuffer.wrap(data);
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                }
                writtenWindows.incrementAndGet();
                writtenBytes.addAndGet(data.length);
            } catch (IOException e) {
                plugin.getLogger().warning("failed to write recording: " + e.getMessage());
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                returnSnapshot(snap);
            }
        }
    }

    private Path newFilePath(long anonPlayerId, long sessionId) {
        String name = datePrefix + "_" + Long.toUnsignedString(anonPlayerId, 36)
                + "_" + Long.toUnsignedString(sessionId, 36)
                + "_" + UUID.randomUUID().toString().substring(0, 8)
                + ".gmlml";
        return recordingsDir.resolve(name);
    }

    public void pruneIfOverDiskCap() {
        long cap = config.maxDiskMb();
        if (cap <= 0) {
            return;
        }
        long maxBytes = cap * 1024L * 1024L;
        long used = directorySize(recordingsDir);
        if (used <= maxBytes) {
            return;
        }
        paused.set(true);
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
