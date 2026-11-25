package dev.grimml.detect.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

/** Alert history: entries land in day files, search finds a player by name or UUID newest first, old days are deleted. */
public final class HistorySelfCheck {

    private static final long DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("grimml-history");
        long now = 1_795_600_000_000L; // 2026-11-25
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        AlertHistory h = new AlertHistory(dir, true, 7, Logger.getLogger("test"));
        h.record(new AlertHistory.Entry(now - 10 * DAY, steve, "Steve", "MLKillAura", 70, 1, 9, 30, null));
        h.record(new AlertHistory.Entry(now - 2 * DAY, steve, "Steve", "MLKillAura", 74, 2, 12, 30, null));
        h.record(new AlertHistory.Entry(now - 60_000, alex, "Alex", "SwapOrder", 100, 1, -1, -1, null));
        h.record(new AlertHistory.Entry(now - 30_000, steve, "Steve", "SwapOrder", 100, 1, -1, -1, null));
        h.record(new AlertHistory.Entry(now - 20_000, steve, "Steve", "SwapOrder", 100, 3, -1, -1, "instant-lunge"));
        check(h.recentSince(now - 120_000).size() == 3, "in memory since 2 min ago");
        h.shutdown(); // flushes the writes

        check(Files.exists(dir.resolve(AlertHistory.fileName(now))), "today's file");
        AlertHistory.Result r = h.searchNow("steve", 30, 10, now);
        check(r.total() == 4 && r.entries().get(0).punishment() && r.entries().get(0).group().equals("instant-lunge"),
                "newest first, punishment kept: " + r);
        check(r.entries().get(2).flagged() == 12 && r.entries().get(1).windows() == -1, "counts kept: " + r);
        check(h.searchNow(steve.toString(), 30, 10, now).total() == 4, "by UUID");
        check(h.searchNow("Steve", 7, 2, now).total() == 3 && h.searchNow("Steve", 7, 2, now).entries().size() == 2,
                "days and max");
        check(h.searchNow("nobody", 30, 10, now).total() == 0, "unknown player");
        check(h.searchNow("Ste", 30, 10, now).total() == 0, "a name must match whole");

        Files.writeString(dir.resolve(AlertHistory.fileName(now)), "{\"t\":1,\"uuid\":\"bro", java.nio.file.StandardOpenOption.APPEND);
        check(h.searchNow("Steve", 30, 10, now).total() == 4, "a broken last line is skipped");

        check(h.pruneNow(now) == 1 && h.searchNow("Steve", 30, 10, now).total() == 3, "10-day-old file deleted");
        System.out.println("history self-check ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
