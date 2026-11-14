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
import java.util.concurrent.atomic.AtomicInteger;
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

    // bumped when recorder behaviour changes what the files mean; written to sessions.idx at start
    // 2: per-window validity, PARTIAL only on real data loss, no duplicate knockback/totem events,
    //    one file per player per cycle, live flags clear, per-player world, real file date
    public static final int RECORDER_REV = 2;

    // each guard owns one bit so the lag guard recovering cannot undo a disk-cap or manual pause
    public static final int PAUSE_MANUAL = 1;
    public static final int PAUSE_MSPT = 1 << 1;
    public static final int PAUSE_DISK = 1 << 2;
    private final AtomicInteger pauseBits = new AtomicInteger();
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
        // 64 overflowed during a burst of flags and silently dropped mark/join lines
        this.sidecarQueue = new ArrayBlockingQueue<>(1024);
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
        if (!running.get() || isPaused()) {
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

    public void setPaused(int reason, boolean pause) {
        pauseBits.updateAndGet(v -> pause ? v | reason : v & ~reason);
    }

    // /grimml pause
    public void setPaused(boolean pause) {
        setPaused(PAUSE_MANUAL, pause);
    }

    // /grimml resume: an explicit operator override; a guard whose condition still holds pauses again
    public void resumeAll() {
        pauseBits.set(0);
    }

    public boolean isPaused() {
        return pauseBits.get() != 0;
    }

    public boolean isPaused(int reason) {
        return (pauseBits.get() & reason) != 0;
    }

    public String pauseReasons() {
        int b = pauseBits.get();
        if (b == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        if ((b & PAUSE_MANUAL) != 0) sb.append("manual ");
        if ((b & PAUSE_MSPT) != 0) sb.append("mspt ");
        if ((b & PAUSE_DISK) != 0) sb.append("disk ");
        return sb.toString().trim();
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
        if (!isPaused()) {
            FightAssembler a = assembler.get();
            if (a != null) {
                a.assemble();
            }
        }
        drainSidecars();
        CombatSnapshot snap;
        while ((snap = queue.poll()) != null) {
            if (isPaused()) {
                pool.giveBack(snap);
                continue;
            }
            Path file = null;
            try {
                file = files.newFilePath(snap.anonPlayerId, snap.sessionId, snap.markId);
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
        byte[] line = sidecarQueue.poll();
        if (line == null) {
            return;
        }
        Path idx = files.dir().resolve("sessions.idx");
        // one open and one write for the whole batch (was open + append + close for every single line)
        try (java.io.OutputStream out = new java.io.BufferedOutputStream(
                Files.newOutputStream(idx, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 8192)) {
            do {
                out.write(line);
            } while ((line = sidecarQueue.poll()) != null);
        } catch (IOException e) {
            plugin.getLogger().warning("failed to write session index: " + e.getMessage());
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
