package dev.grimml.detect.scan;

import dev.grimml.detect.features.Features;
import dev.grimml.detect.model.ModelSet;
import dev.grimml.detect.model.ModuleModel;
import dev.grimml.detect.track.PlayerTrack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Runs on one background thread every scan interval (2 s, the recorder's writer cadence, so live windows
 * overlap the way training windows did). For each player who attacked within the last window:
 *   window (last 160 frames + attacks in the last 8 s) -> 13 features -> each module's score.
 * A window counts as flagged when its score passes the module's window threshold. Windows are grouped into
 * fights (a gap of fightGapMs without attacks starts a new one). A fight is flagged once at least minWindows
 * were scored and the flagged share reaches the module's alert share; the counters then restart, so a long
 * fight can flag again.
 */
public final class Scanner implements Runnable {

    public interface Sink {
        void flag(UUID player, ModuleModel module, String verbose);
    }

    public record Settings(int frameCap, int minAttacks, int minFrames, long fightGapMs) {
    }

    private static final class Fight {
        long lastSeenNanos;
        int windows;
        int flagged;
        double scoreSum;
    }

    private final Map<UUID, PlayerTrack> tracks;
    private final Map<UUID, Boolean> exempt;
    private final Supplier<ModelSet> models;
    private final Sink sink;
    private final PlayerTrack.Window w;
    private final Map<UUID, Map<String, Fight>> fights = new HashMap<>();
    private volatile Settings settings;

    public final AtomicLong windowsScored = new AtomicLong();
    public final AtomicLong flagsRaised = new AtomicLong();
    public volatile double lastRunMs;

    public Scanner(Map<UUID, PlayerTrack> tracks, Map<UUID, Boolean> exempt, Supplier<ModelSet> models, Sink sink,
                   Settings settings, int attackCap) {
        this.tracks = tracks;
        this.exempt = exempt;
        this.models = models;
        this.sink = sink;
        this.settings = settings;
        this.w = new PlayerTrack.Window(settings.frameCap(), attackCap);
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    @Override
    public void run() {
        long t0 = System.nanoTime();
        try {
            scan(t0);
        } catch (Throwable t) {
            t.printStackTrace(); // never let the scheduled task die
        }
        lastRunMs = (System.nanoTime() - t0) / 1e6;
    }

    private void scan(long now) {
        Settings s = settings;
        ModelSet set = models.get();
        long windowNs = s.frameCap() * 50_000_000L;
        long gapNs = s.fightGapMs() * 1_000_000L;
        fights.keySet().removeIf(id -> !tracks.containsKey(id));

        for (var e : tracks.entrySet()) {
            UUID id = e.getKey();
            PlayerTrack t = e.getValue();
            long last = t.lastAttackNanos();
            if (last == 0 || now - last > windowNs || Boolean.TRUE.equals(exempt.get(id))) {
                continue;
            }
            t.snapshot(w, now - windowNs, now);
            if (w.m < s.minAttacks() || w.n < s.minFrames()) {
                continue;
            }
            double[] f = Features.compute(w.yaw, w.pitch, w.x, w.z, w.flags, w.n, w.attacks, w.m);
            windowsScored.incrementAndGet();
            Map<String, Fight> mine = fights.computeIfAbsent(id, k -> new HashMap<>());
            for (ModuleModel m : set.models()) {
                if (!m.enabled()) {
                    continue;
                }
                double score = m.score(f);
                Fight fight = mine.computeIfAbsent(m.checkName(), k -> new Fight());
                if (fight.lastSeenNanos != 0 && now - fight.lastSeenNanos > gapNs) {
                    fight.windows = 0;
                    fight.flagged = 0;
                    fight.scoreSum = 0;
                }
                fight.lastSeenNanos = now;
                fight.windows++;
                fight.scoreSum += score;
                if (score >= m.windowThreshold()) {
                    fight.flagged++;
                }
                double share = (double) fight.flagged / fight.windows;
                if (fight.windows >= m.minWindows() && share >= m.alertShare()) {
                    String verbose = String.format("%d/%d windows (%.0f%%, line %.0f%%) avg %.2f",
                            fight.flagged, fight.windows, share * 100, m.alertShare() * 100, fight.scoreSum / fight.windows);
                    flagsRaised.incrementAndGet();
                    sink.flag(id, m, verbose);
                    fight.windows = 0;
                    fight.flagged = 0;
                    fight.scoreSum = 0;
                }
            }
        }
    }
}
