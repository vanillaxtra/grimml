package dev.grimml.writer;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.config.PluginConfig;
import dev.grimml.format.BinaryFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final SnapshotPool pool;
    private final RecordingFiles files;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedWindows = new AtomicLong(0);
    private final AtomicLong writtenWindows = new AtomicLong(0);
    private final AtomicLong writtenBytes = new AtomicLong(0);

    public RecordWriter(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.writerQueueCapacity());
        this.files = new RecordingFiles(plugin, config);
        this.pool = new SnapshotPool(config);
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

    // called once per sample period for each online player; snapshots only if combat is active
    public void offerSnapshotIfCombat(PlayerRingBuffer buf) {
        CombatSnapshot snap = pool.borrow();
        long now = System.nanoTime();
        long preNanos = config.preCombatSeconds() * 1_000_000_000L;
        if (!buf.trySnapshot(snap, now, preNanos)) {
            pool.giveBack(snap);
            return;
        }
        if (!queue.offer(snap)) {
            droppedWindows.incrementAndGet();
            pool.giveBack(snap);
        }
    }

    public Path recordingsDir() {
        return files.dir();
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
                pool.giveBack(snap);
                continue;
            }
            Path file = null;
            try {
                file = files.newFilePath(snap.anonPlayerId, snap.sessionId);
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
                pool.giveBack(snap);
            }
        }
    }
}
