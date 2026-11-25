package dev.grimml.detect.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Every alert and punishment, kept on disk for keepDays: one JSON line per entry in history/<yyyy-mm-dd>.jsonl (UTC days).
 * Writes and reads run on one background thread; the last RECENT entries are also kept in memory for alerts on join.
 *
 * No Bukkit here, so it can be tested on its own.
 */
public final class AlertHistory {

    // group is null for an alert, the punishment group's name for a punishment
    public record Entry(long ms, UUID uuid, String name, String check, int confidence, int vl, int flagged,
                        int windows, String group) {

        public boolean punishment() {
            return group != null;
        }

        String toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("t", ms);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", name);
            o.addProperty("check", check);
            o.addProperty("conf", confidence);
            o.addProperty("vl", vl);
            if (windows >= 0) {
                o.addProperty("flagged", flagged);
                o.addProperty("windows", windows);
            }
            if (group != null) {
                o.addProperty("punish", group);
            }
            return o.toString();
        }

        static Entry fromJson(String line) {
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                return new Entry(o.get("t").getAsLong(), UUID.fromString(o.get("uuid").getAsString()),
                        o.get("name").getAsString(), o.get("check").getAsString(), o.get("conf").getAsInt(),
                        o.get("vl").getAsInt(), o.has("flagged") ? o.get("flagged").getAsInt() : -1,
                        o.has("windows") ? o.get("windows").getAsInt() : -1,
                        o.has("punish") ? o.get("punish").getAsString() : null);
            } catch (RuntimeException e) {
                return null; // a line cut short by a crash
            }
        }
    }

    private static final int RECENT = 500;

    private final Path dir;
    private final Logger log;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GrimML-history");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean enabled;
    private volatile int keepDays;

    public AlertHistory(Path dir, boolean enabled, int keepDays, Logger log) {
        this.dir = dir;
        this.log = log;
        settings(enabled, keepDays);
    }

    public void settings(boolean enabled, int keepDays) {
        this.enabled = enabled;
        this.keepDays = Math.max(1, keepDays);
    }

    public boolean enabled() {
        return enabled;
    }

    static String fileName(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC) + ".jsonl";
    }

    // any thread: keeps it in memory and appends it to today's file
    public void record(Entry e) {
        synchronized (recent) {
            recent.addLast(e);
            while (recent.size() > RECENT) {
                recent.removeFirst();
            }
        }
        if (!enabled) {
            return;
        }
        String line = e.toJson() + "\n";
        io.execute(() -> {
            try {
                Files.createDirectories(dir);
                try (Writer w = Files.newBufferedWriter(dir.resolve(fileName(e.ms())), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                }
            } catch (IOException ex) {
                log.warning("could not write alert history: " + ex.getMessage());
            }
        });
    }

    // entries since sinceMs that are still in memory (this run only), oldest first
    public List<Entry> recentSince(long sinceMs) {
        List<Entry> out = new ArrayList<>();
        synchronized (recent) {
            for (Entry e : recent) {
                if (e.ms() >= sinceMs) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    // deletes day files older than keepDays (in the background)
    public void prune(long nowMs) {
        io.execute(() -> pruneNow(nowMs));
    }

    int pruneNow(long nowMs) {
        LocalDate oldest = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC).minusDays(keepDays - 1L);
        int removed = 0;
        for (Path f : dayFiles()) {
            LocalDate d = dayOf(f);
            if (d != null && d.isBefore(oldest)) {
                try {
                    Files.deleteIfExists(f);
                    removed++;
                } catch (IOException ex) {
                    log.warning("could not delete old alert history " + f.getFileName() + ": " + ex.getMessage());
                }
            }
        }
        return removed;
    }

    /**
     * A player's entries in the last `days` days, newest first, at most max. who is a name (any case) or a UUID.
     * The answer comes on the history thread; total counts every match, not just the ones returned.
     */
    public void search(String who, int days, int max, long nowMs, Consumer<Result> done) {
        io.execute(() -> done.accept(searchNow(who, days, max, nowMs)));
    }

    public record Result(List<Entry> entries, int total, String name) {
    }

    Result searchNow(String who, int days, int max, long nowMs) {
        String key = who.toLowerCase(Locale.ROOT);
        long since = nowMs - days * 86_400_000L;
        List<Path> files = dayFiles();
        files.sort(Collections.reverseOrder()); // newest day first
        List<Entry> out = new ArrayList<>();
        int total = 0;
        String name = null;
        for (Path f : files) {
            LocalDate d = dayOf(f);
            if (d == null || d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() < since) {
                continue;
            }
            List<Entry> day = new ArrayList<>();
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.toLowerCase(Locale.ROOT).contains(key)) {
                        continue; // cheap filter before parsing
                    }
                    Entry e = Entry.fromJson(line);
                    if (e != null && e.ms() >= since
                            && (e.name().equalsIgnoreCase(who) || e.uuid().toString().equalsIgnoreCase(who))) {
                        day.add(e);
                    }
                }
            } catch (IOException ex) {
                log.warning("could not read alert history " + f.getFileName() + ": " + ex.getMessage());
            }
            Collections.reverse(day); // a file is oldest first
            for (Entry e : day) {
                total++;
                if (name == null) {
                    name = e.name(); // the newest name they used
                }
                if (out.size() < max) {
                    out.add(e);
                }
            }
        }
        return new Result(out, total, name);
    }

    private List<Path> dayFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            s.filter(p -> dayOf(p) != null).forEach(out::add);
        } catch (IOException ex) {
            log.warning("could not list alert history: " + ex.getMessage());
        }
        return out;
    }

    private static LocalDate dayOf(Path f) {
        String n = f.getFileName().toString();
        if (!n.endsWith(".jsonl") || n.length() != 16) {
            return null;
        }
        try {
            return LocalDate.parse(n.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // waits for pending writes
    public void shutdown() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
