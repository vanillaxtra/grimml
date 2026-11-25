package dev.grimml.detect.overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one player's overlay says: a line per check, newest alerts first.
 *
 * Flagged lines come from alerts and stay for keepMs after the last one (rule checks count how often).
 * Watching lines come from an ML fight in progress that has flagged windows but has not alerted yet; they go away
 * when the fight is judged or nothing was scored for liveKeepMs.
 *
 * Plain data, no Bukkit: the scan thread writes it and renders it.
 */
public final class OverlayLines {

    public enum Kind { FLAGGED, WATCHING, RULE }

    // one rendered line: flagged and windows are -1 for rule checks
    public record Line(Kind kind, String check, double confidence, int flagged, int windows, int count) {
    }

    private static final class Entry {
        double confidence;
        int flagged;
        int windows;
        int count;
        long alertMs;      // 0 = never alerted
        boolean rule;
        // the fight in progress
        double liveMean;
        int liveFlagged;
        int liveWindows;
        long liveMs;
    }

    private final Map<String, Entry> checks = new HashMap<>();

    // an alert: ML checks pass the fight's counts, rule checks -1
    public synchronized void alert(String check, double confidence, int flagged, int windows, long nowMs) {
        Entry e = checks.computeIfAbsent(check, k -> new Entry());
        e.confidence = confidence;
        e.flagged = flagged;
        e.windows = windows;
        e.rule = windows < 0;
        e.count++;
        e.alertMs = nowMs;
    }

    // an ML fight after a scored window
    public synchronized void live(String check, int windows, int flagged, double mean, long nowMs) {
        Entry e = checks.get(check);
        if (e == null) {
            if (flagged <= 0) {
                return;
            }
            e = new Entry();
            checks.put(check, e);
        }
        e.liveWindows = windows;
        e.liveFlagged = flagged;
        e.liveMean = mean;
        e.liveMs = nowMs;
    }

    // drops what is too old; true when nothing is left
    public synchronized boolean prune(long nowMs, long keepMs, long liveKeepMs) {
        checks.values().removeIf(e -> {
            boolean flagged = e.alertMs != 0 && nowMs - e.alertMs <= keepMs;
            boolean live = e.liveFlagged > 0 && nowMs - e.liveMs <= liveKeepMs;
            return !flagged && !live;
        });
        return checks.isEmpty();
    }

    // the lines to show, at most max: flagged ones (newest alert first), then watching ones (highest share first)
    public synchronized List<Line> lines(long nowMs, long keepMs, long liveKeepMs, boolean showWatching, int max) {
        List<Map.Entry<String, Entry>> flagged = new ArrayList<>();
        List<Map.Entry<String, Entry>> watching = new ArrayList<>();
        for (var en : checks.entrySet()) {
            Entry e = en.getValue();
            if (e.alertMs != 0 && nowMs - e.alertMs <= keepMs) {
                flagged.add(en);
            } else if (showWatching && e.liveFlagged > 0 && nowMs - e.liveMs <= liveKeepMs) {
                watching.add(en);
            }
        }
        flagged.sort((a, b) -> Long.compare(b.getValue().alertMs, a.getValue().alertMs));
        watching.sort((a, b) -> Double.compare(share(b.getValue()), share(a.getValue())));
        List<Line> out = new ArrayList<>();
        for (var en : flagged) {
            Entry e = en.getValue();
            out.add(new Line(e.rule ? Kind.RULE : Kind.FLAGGED, en.getKey(), e.confidence, e.flagged, e.windows, e.count));
        }
        for (var en : watching) {
            Entry e = en.getValue();
            out.add(new Line(Kind.WATCHING, en.getKey(), e.liveMean, e.liveFlagged, e.liveWindows, e.count));
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, Math.max(0, max))) : out;
    }

    private static double share(Entry e) {
        return e.liveWindows == 0 ? 0.0 : (double) e.liveFlagged / e.liveWindows;
    }
}
