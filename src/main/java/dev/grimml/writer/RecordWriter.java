package dev.grimml.writer;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.config.PluginConfig;
import dev.grimml.fight.FightAssembler;
import dev.grimml.format.BinaryFormat;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ArrayBlockingQueue<byte[]> sidecarQueue;
    private final SnapshotPool pool;
    private final RecordingFiles files;
    private final DropCounters drops = new DropCounters();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong writtenWindows = new AtomicLong(0);
    private final AtomicLong writtenBytes = new AtomicLong(0);
    private final AtomicReference<FightAssembler> assembler = new AtomicReference<>();
    private volatile String mcVersion = "";
    private volatile String grimVersion = "";
    private volatile String peVersion = "";
    private volatile String worldName = "";

    public RecordWriter(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.writerQueueCapacity());
        this.sidecarQueue = new ArrayBlockingQueue<>(64);
        this.files = new RecordingFiles(plugin, config);
        this.pool = new SnapshotPool(config);
        scheduler.scheduleAtFixedRate(this::cycle, config.writerIntervalMs(), config.writerIntervalMs(), TimeUnit.MILLISECONDS);
    }

    public void setAssembler(FightAssembler a) {
        assembler.set(a);
    }

    public void setVersions(String mc, String grim, String pe) {
        this.mcVersion = mc == null ? "" : mc;
        this.grimVersion = grim == null ? "" : grim;
        this.peVersion = pe == null ? "" : pe;
    }

    public void setWorldName(String world) {
        this.worldName = world == null ? "" : world;
    }

    public String mcVersion() { return mcVersion; }
    public String grimVersion() { return grimVersion; }
    public String peVersion() { return peVersion; }
    public String worldName() { return worldName; }

    public void stop() {
        running.set(false);
        scheduler.shutdown();
        cycle();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    public CombatSnapshot borrow() {
        return pool.borrow();
    }

    public void giveBack(CombatSnapshot snap) {
        pool.giveBack(snap);
    }

    public void offerAssembled(CombatSnapshot snap) {
        if (!running.get() || paused.get()) {
            pool.giveBack(snap);
            return;
        }
        if (!queue.offer(snap)) {
            drops.window();
            pool.giveBack(snap);
        }
    }

    public void offerSidecar(byte[] line) {
        if (!sidecarQueue.offer(line)) {
            drops.type(0);
        }
    }

    public Path recordingsDir() {
        return files.dir();
    }

    public DropCounters drops() {
        return drops;
    }

    public void setPaused(boolean pause) {
        paused.set(pause);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public long droppedWindows() {
        return drops.windows.get();
    }

    public long writtenWindows() {
        return writtenWindows.get();
    }

    public long writtenBytes() {
        return writtenBytes.get();
    }

    private void cycle() {
        if (!paused.get()) {
            FightAssembler a = assembler.get();
            if (a != null) {
                a.assemble();
            }
        }
        drainSidecars();
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

    private void drainSidecars() {
        Path idx = files.dir().resolve("sessions.idx");
        byte[] line;
        while ((line = sidecarQueue.poll()) != null) {
            try {
                Files.write(idx, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().warning("failed to write session index: " + e.getMessage());
            }
        }
    }

    public void enqueueSessionLine(String line) {
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        offerSidecar(b);
    }

    public GrimMLPlugin plugin() {
        return plugin;
    }

    public static String pluginVersion(String name) {
        var p = Bukkit.getPluginManager().getPlugin(name);
        return p == null ? "" : p.getPluginMeta().getVersion();
    }
}
