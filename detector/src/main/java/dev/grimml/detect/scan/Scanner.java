package dev.grimml.detect.scan;

import dev.grimml.detect.features.AimGrid;
import dev.grimml.detect.features.Features;
import dev.grimml.detect.features.LungeRate;
import dev.grimml.detect.features.Schema4Features;
import dev.grimml.detect.features.Window4;
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
        /**
         * confidence (0-1) is what the alert shows players: the model's average cheat score over the fight for ML
         * checks; for rule checks the share of the player's recent actions that matched the cheat's pattern.
         */
        void flag(UUID player, String checkName, String description, double confidence, String verbose);
    }

    public record Settings(int frameCap, int minAttacks, int minFrames, long fightGapMs) {
    }

    /** The AimGrid check (features/AimGrid.Live): a fight-level sequential test on off-grid mouse rotation. */
    public record GridSettings(boolean enabled, double p0, double p1, double h, double minBias, long cooldownMs) {
        public static final String CHECK = "AimGrid";
        public static final String DESCRIPTION = "Rotation off the player's mouse sensitivity grid, pulling toward the opponent";
    }

    /**
     * The live lunge checks (features/LungeRate): LungeRate (swap-lunges chained faster than a hand can), LungeHold
     * (the spear held for exactly one client tick, again and again) and SwapOrder (a hotbar swap after an attack in the
     * same client tick, which vanilla never sends).
     */
    public record LungeSettings(boolean rateEnabled, double fastMs, double minMs, int count, double windowMs,
                                boolean holdEnabled, int holdCount, int holdOf,
                                boolean orderEnabled, int orderCount, double orderWindowMs,
                                boolean axeEnabled, int axeCount, int axeOf, long cooldownMs) {
        public static final String[] CHECKS = {"LungeRate", "LungeHold", "SwapOrder", "AxeHold"};
        public static final String[] DESCRIPTIONS = {
                "Spear swap-lunges chained faster than a hand can (Instant Lunge)",
                "Spear swapped in, jabbed and swapped out within one client tick, again and again (Instant Lunge)",
                "Hotbar swap sent after an attack in the same client tick, which the vanilla client never does",
                "Axe swapped away one client tick after the shield hit, again and again (Auto Shield Breaker)"};

        public boolean enabled() {
            return rateEnabled || holdEnabled || orderEnabled || axeEnabled;
        }

        boolean on(int check) {
            return switch (check) {
                case 0 -> rateEnabled;
                case 1 -> holdEnabled;
                case 2 -> orderEnabled;
                default -> axeEnabled;
            };
        }
    }

    private static final class Lunge {
        final LungeRate rate;
        final long[] lastAlertNanos = new long[4];

        Lunge(LungeSettings l) {
            rate = new LungeRate(l.fastMs(), l.minMs(), l.count(), l.windowMs(), 150.0,
                    l.holdCount(), l.holdOf(), l.orderCount(), l.orderWindowMs(), l.axeCount(), l.axeOf());
        }
    }

    private static final class Grid {
        final AimGrid.Live live;
        long lastAlertNanos;

        Grid(GridSettings g) {
            live = new AimGrid.Live(g.p0(), g.p1(), g.h(), g.minBias());
        }
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
    private final Window4 w4;
    private final double[] combined = new double[Features.NAMES.length + Schema4Features.NAMES.length];
    private final Map<UUID, Map<String, Fight>> fights = new HashMap<>();
    private final Map<UUID, Grid> grids = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Lunge> lunges = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean[] hasAim;
    private double[] errYaw;
    private volatile Settings settings;
    private volatile GridSettings gridSettings;
    private volatile LungeSettings lungeSettings;

    public final AtomicLong windowsScored = new AtomicLong();
    public final AtomicLong flagsRaised = new AtomicLong();
    public volatile double lastRunMs;

    public Scanner(Map<UUID, PlayerTrack> tracks, Map<UUID, Boolean> exempt, Supplier<ModelSet> models, Sink sink,
                   Settings settings, GridSettings gridSettings, LungeSettings lungeSettings, int attackCap) {
        this.gridSettings = gridSettings;
        this.lungeSettings = lungeSettings;
        this.tracks = tracks;
        this.exempt = exempt;
        this.models = models;
        this.sink = sink;
        this.settings = settings;
        this.w = new PlayerTrack.Window(settings.frameCap(), attackCap);
        this.w4 = new Window4(settings.frameCap(), attackCap);
        this.hasAim = new boolean[settings.frameCap()];
        this.errYaw = new double[settings.frameCap()];
    }

    public void settings(Settings s, GridSettings g, LungeSettings l) {
        if (s.frameCap() != settings.frameCap()) {
            throw new IllegalArgumentException("window.frames needs a restart");
        }
        boolean changed = !g.equals(gridSettings);
        this.settings = s;
        this.gridSettings = g;
        if (changed) {
            grids.clear(); // relearn with the new parameters (next scan, on the scan thread)
        }
        if (!l.equals(lungeSettings)) {
            lunges.clear();
        }
        this.lungeSettings = l;
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
        GridSettings g = gridSettings;
        LungeSettings ls = lungeSettings;
        fights.keySet().removeIf(id -> !tracks.containsKey(id));
        grids.keySet().removeIf(id -> !tracks.containsKey(id));
        lunges.keySet().removeIf(id -> !tracks.containsKey(id));

        for (var e : tracks.entrySet()) {
            UUID id = e.getKey();
            PlayerTrack t = e.getValue();
            if (Boolean.TRUE.equals(exempt.get(id))) {
                continue;
            }
            long last = t.lastAttackNanos();
            boolean attacked = last != 0 && now - last <= windowNs;
            if (!attacked && !g.enabled() && !ls.enabled()) {
                continue;
            }
            t.snapshot(w, w4, now - windowNs, now);
            if (g.enabled()) {
                grid(id, g, now);   // every player: the step is learned before the first fight
            }
            if (ls.enabled()) {
                lunge(id, ls, now); // every player: a spear jab is not an attack, lunge-only players never "attack"
            }
            if (!attacked || w.m < s.minAttacks() || w.n < s.minFrames()) {
                continue;
            }
            double[] core = Features.compute(w.yaw, w.pitch, w.x, w.z, w.flags, w.n, w.attacks, w.m);
            System.arraycopy(core, 0, combined, 0, core.length);
            boolean need4 = false;
            for (ModuleModel m : set.models()) {
                need4 |= m.enabled() && m.usesSchema4();
            }
            java.util.Arrays.fill(combined, core.length, combined.length, Double.NaN);
            if (need4) {
                double[] s4 = Schema4Features.compute(w4, true);
                System.arraycopy(s4, 0, combined, core.length, s4.length);
            }
            double[] f = combined;
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
                    String verbose = String.format("%d of %d fight windows flagged (%.0f%%, alert line %.0f%%)",
                            fight.flagged, fight.windows, share * 100, m.alertShare() * 100);
                    flagsRaised.incrementAndGet();
                    sink.flag(id, m.checkName(), m.description(), fight.scoreSum / fight.windows, verbose);
                    fight.windows = 0;
                    fight.flagged = 0;
                    fight.scoreSum = 0;
                }
            }
        }
    }

    /** Feeds the frames this player made since the last scan (the ring holds 8 s, scans run every 2 s). */
    private void grid(UUID id, GridSettings g, long now) {
        Grid gr = grids.computeIfAbsent(id, k -> new Grid(g));
        Schema4Features.aimErrors(w4, hasAim, errYaw);
        boolean alert = false;
        for (int i = 0; i < w4.n; i++) {
            if (w4.tick[i] <= gr.live.lastTick) {
                continue;
            }
            alert |= gr.live.feed(w4.tick[i], w4.hasRot[i], w4.yawExact[i], w4.pitchExact[i], w4.hasOpp[i], hasAim[i], errYaw[i]);
        }
        if (alert && now - gr.lastAlertNanos >= g.cooldownMs() * 1_000_000L) {
            gr.lastAlertNanos = now;
            flagsRaised.incrementAndGet();
            double toward = Math.max(0.0, Math.min(1.0, (1.0 + gr.live.bias()) / 2.0));
            sink.flag(id, GridSettings.CHECK, GridSettings.DESCRIPTION, toward, String.format(
                    "%.0f%% of recent rotation off the mouse grid, %.0f%% of that toward the opponent",
                    gr.live.offShare() * 100, toward * 100));
        }
    }

    /** Feeds the swaps, attacks and spear STABs this player sent since the last scan. */
    private void lunge(UUID id, LungeSettings l, long now) {
        Lunge lu = lunges.computeIfAbsent(id, k -> new Lunge(l));
        LungeRate r = lu.rate;
        int alerts = 0;
        long last = r.lastNanos;
        for (int i = 0; i < w4.m; i++) {
            long n = w4.eNanos[i];
            if (n <= last) {
                continue;
            }
            int type = w4.eType[i];
            int ct = w4.eClientTick[i];
            if (type == Window4.HOTBAR_SWAP && w4.eA[i] != 40) {
                alerts |= r.swap(n, (w4.eC[i] >> 8) & 0xFF, w4.eC[i] & 0xFF, ct);
                r.lastNanos = n;
            } else if (type == Window4.BREAK && w4.eA[i] == 8) {
                alerts |= r.stab(n, ct);
                r.lastNanos = n;
            } else if (type == Window4.ATTACK && ((w4.eG[i] & 0xFF) == 1 || (w4.eG[i] == 0 && w4.eA[i] != 0))) {
                r.hit(n, ct);
                r.lastNanos = n;
            }
        }
        for (int c = 0; c < 4; c++) {
            if ((alerts & (1 << c)) == 0 || !l.on(c) || now - lu.lastAlertNanos[c] < l.cooldownMs() * 1_000_000L) {
                continue;
            }
            lu.lastAlertNanos[c] = now;
            flagsRaised.incrementAndGet();
            String verbose = switch (c) {
                case 0 -> String.format("%d spear swap-lunges under %.0f ms apart within %.0f s",
                        l.count(), l.fastMs(), l.windowMs() / 1000);
                case 1 -> String.format("spear held one tick on %.0f%% of the last %d swap-lunges",
                        r.holdShare * 100, l.holdOf());
                case 2 -> String.format("%d hotbar swaps sent after an attack in the same tick within %.0f s",
                        l.orderCount(), l.orderWindowMs() / 1000);
                default -> String.format("axe swapped away one tick after the hit on %.0f%% of the last %d axe hits",
                        r.axeShare * 100, l.axeOf());
            };
            double conf = switch (c) {
                case 0 -> r.rateShare;
                case 1 -> r.holdShare;
                case 2 -> 1.0; // vanilla never sends this order
                default -> r.axeShare;
            };
            sink.flag(id, LungeSettings.CHECKS[c], LungeSettings.DESCRIPTIONS[c], conf, verbose);
        }
    }

    /** Learned step and current score per player, for /gmld status. */
    public String gridStatus(UUID id) {
        Grid gr = grids.get(id);
        return gr == null ? null : String.format("step %.5f score %.1f", gr.live.step(), gr.live.score());
    }
}
