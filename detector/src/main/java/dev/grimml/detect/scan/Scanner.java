package dev.grimml.detect.scan;

import dev.grimml.detect.features.AimGrid;
import dev.grimml.detect.features.ComboRepeat;
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
 * This is the scanner that runs all the checks.
 * It runs on one background thread every 2 seconds (same as the recorder, so live windows overlap like the training ones).
 *
 * For each player who attacked in the last 8 seconds it builds the window, works out the features and scores every model.
 * A window is flagged when its score passes the model's window threshold, and the windows are grouped into fights (see Fight).
 * It also feeds the rule checks (AimGrid, the lunge checks and the combo repeat checks) for every player, fighting or not.
 */
public final class Scanner implements Runnable {

    public interface Sink {

        // an alert; flagged and windows are the fight's counts for ML checks and -1 for rule checks
        void flag(UUID player, String checkName, String description, double confidence, String verbose,
                  int flagged, int windows);

        // an ML fight in progress after each scored window (for the staff overlay); counts reset when it is judged
        default void live(UUID player, String checkName, int windows, int flagged, double meanScore) {
        }

        // called after every scan, on the scan thread
        default void scanned() {
        }
    }

    public record Settings(int frameCap, int minAttacks, int minFrames, long fightGapMs, int blockWindows,
                           int judgeMinWindows, double earlyZ, int earlyMinWindows) {
        public Settings {
            judgeMinWindows = Math.max(1, judgeMinWindows);
            earlyZ = Math.max(0.0, earlyZ);
            earlyMinWindows = Math.max(1, earlyMinWindows);
            blockWindows = Math.max(judgeMinWindows, blockWindows);
        }
    }

    public record GridSettings(boolean enabled, double p0, double p1, double h, double minBias, long cooldownMs,
                               double baseMargin, double baseMax, int baseMin, double maxSteps) {
        public static final String CHECK = "AimGrid";
        public static final String DESCRIPTION = "Rotation off the player's mouse sensitivity grid, pulling toward the opponent";
    }

    public record LungeSettings(boolean rateEnabled, double fastMs, double minMs, int count, double windowMs,
                                boolean holdEnabled, int holdCount, int holdOf,
                                boolean orderEnabled, int orderCount, double orderWindowMs,
                                boolean axeEnabled, int axeCount, int axeOf,
                                boolean stunEnabled, boolean slamEnabled, boolean webEnabled, int comboCount, int comboOf,
                                long cooldownMs) {
        public static final String[] CHECKS = {"LungeRate", "LungeHold", "SwapOrder", "AxeHold",
                "StunRepeat", "SlamRepeat", "WebRepeat"};
        public static final String[] DESCRIPTIONS = {
                "Spear swap-lunges chained faster than a hand can (Instant Lunge)",
                "Spear swapped in, jabbed and swapped out within one client tick, again and again (Instant Lunge)",
                "Hotbar swap sent after an attack in the same client tick, which the vanilla client never does",
                "Axe swapped away one client tick after the shield hit, again and again (Auto Shield Breaker)",
                "The same stun (axe in, hit, swap, hit) to the client tick, again and again (macro)",
                "The same stun slam (axe hit, mace swap, mace hit) to the client tick, again and again (Auto Stun Slam)",
                "The same stun web (axe hit, cobweb swap, place) to the client tick, again and again (macro)"};

        public boolean enabled() {
            return rateEnabled || holdEnabled || orderEnabled || axeEnabled || stunEnabled || slamEnabled || webEnabled;
        }

        boolean on(int check) {
            return switch (check) {
                case 0 -> rateEnabled;
                case 1 -> holdEnabled;
                case 2 -> orderEnabled;
                case 3 -> axeEnabled;
                case 4 -> stunEnabled;
                case 5 -> slamEnabled;
                default -> webEnabled;
            };
        }
    }

    private static final class Lunge {
        final LungeRate rate;
        final ComboRepeat combo;
        final long[] lastAlertNanos = new long[LungeSettings.CHECKS.length];

        Lunge(LungeSettings l) {
            rate = new LungeRate(l.fastMs(), l.minMs(), l.count(), l.windowMs(), 150.0,
                    l.holdCount(), l.holdOf(), l.orderCount(), l.orderWindowMs(), l.axeCount(), l.axeOf());
            combo = new ComboRepeat(l.comboCount(), l.comboOf());
        }
    }

    private static final class Grid {
        final AimGrid.Live live;
        long lastAlertNanos;

        Grid(GridSettings g) {
            live = new AimGrid.Live(g.p0(), g.p1(), g.h(), g.minBias(), g.baseMargin(), g.baseMax(), g.baseMin(),
                    g.maxSteps());
        }
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
            grids.clear(); // relearn with the new settings on the next scan
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
            sink.scanned();
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
        fights.keySet().removeIf(id -> !tracks.containsKey(id)); // players who left are not judged (their bypass is gone)
        endFights(set, s, now, gapNs);
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
                grid(id, g, now);   // every player, so the step is learned before the first fight
            }
            if (ls.enabled()) {
                lunge(id, ls, now); // every player, a spear jab is not an attack packet
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
                report(id, m, fight.add(now, score, m.windowThreshold(), m.alertShare(), s.blockWindows(),
                        minWindows(s, m), gapNs, s.earlyZ(), s.earlyMinWindows()));
                sink.live(id, m.checkName(), fight.windows(), fight.flagged(), fight.meanScore());
            }
        }
    }

    // the config minimum or the model's own minimum, whichever is higher
    private static int minWindows(Settings s, ModuleModel m) {
        return Math.max(s.judgeMinWindows(), m.minWindows());
    }

    // judges every fight that ended since the last scan
    private void endFights(ModelSet set, Settings s, long now, long gapNs) {
        Map<String, ModuleModel> byCheck = new HashMap<>();
        for (ModuleModel m : set.models()) {
            if (m.enabled()) {
                byCheck.put(m.checkName(), m);
            }
        }
        for (var e : fights.entrySet()) {
            var it = e.getValue().entrySet().iterator();
            while (it.hasNext()) {
                var f = it.next();
                ModuleModel m = byCheck.get(f.getKey());
                if (m == null) {
                    it.remove(); // the check was turned off or its model is gone
                    continue;
                }
                report(e.getKey(), m, f.getValue().expire(now, m.alertShare(), minWindows(s, m), gapNs));
            }
        }
    }

    // sends the alert if the verdict flagged
    private void report(UUID id, ModuleModel m, Fight.Verdict v) {
        if (v == null || !v.alert()) {
            return;
        }
        String verbose = String.format("%d of %d fight windows flagged (%.0f%%, alert line %.0f%%)",
                v.flagged(), v.windows(), v.share() * 100, m.alertShare() * 100);
        flagsRaised.incrementAndGet();
        sink.flag(id, m.checkName(), m.description(), v.meanScore(), verbose, v.flagged(), v.windows());
    }

    // feeds AimGrid the frames since the last scan (the window holds 8 s, scans run every 2 s)
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
                    "%.0f%% of recent rotation off the mouse grid (their own rate outside fights %.1f%%), %.0f%% of that toward the opponent",
                    gr.live.offShare() * 100, gr.live.baseline() * 100, toward * 100), -1, -1);
        }
    }

    // feeds the lunge and combo checks the swaps, attacks, spear stabs and block places since the last scan
    private void lunge(UUID id, LungeSettings l, long now) {
        Lunge lu = lunges.computeIfAbsent(id, k -> new Lunge(l));
        LungeRate r = lu.rate;
        ComboRepeat co = lu.combo;
        int alerts = 0;
        int combo = 0;
        long last = r.lastNanos;
        for (int i = 0; i < w4.m; i++) {
            long n = w4.eNanos[i];
            if (n <= last) {
                continue;
            }
            int type = w4.eType[i];
            int ct = w4.eClientTick[i];
            if (type == Window4.HOTBAR_SWAP && w4.eA[i] != 40) {
                int to = (w4.eC[i] >> 8) & 0xFF, from = w4.eC[i] & 0xFF;
                alerts |= r.swap(n, to, from, ct);
                combo |= co.swap(n, to, from, ct);
                r.lastNanos = n;
            } else if (type == Window4.BREAK && w4.eA[i] == 8) {
                alerts |= r.stab(n, ct);
                r.lastNanos = n;
            } else if (type == Window4.ATTACK && ((w4.eG[i] & 0xFF) == 1 || (w4.eG[i] == 0 && w4.eA[i] != 0))) {
                r.hit(n, ct);
                combo |= co.hit(n, ct, (w4.eC[i] >> 8) & 0xFF, w4.eB[i]);
                r.lastNanos = n;
            } else if (type == Window4.BLOCK_PLACE) {
                combo |= co.place(n, ct);
                r.lastNanos = n;
            }
        }
        alerts |= combo << 4; // the combo checks are 4-6
        for (int c = 0; c < LungeSettings.CHECKS.length; c++) {
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
                case 3 -> String.format("axe swapped away one tick after the hit on %.0f%% of the last %d axe hits",
                        r.axeShare * 100, l.axeOf());
                default -> comboVerbose(co, c - 4, l.comboOf());
            };
            double conf = switch (c) {
                case 0 -> r.rateShare;
                case 1 -> r.holdShare;
                case 2 -> 1.0; // vanilla never sends this order
                case 3 -> r.axeShare;
                default -> co.share[c - 4];
            };
            sink.flag(id, LungeSettings.CHECKS[c], LungeSettings.DESCRIPTIONS[c], conf, verbose, -1, -1);
        }
    }

    private static final String[] COMBO_STEPS = {"follow-up hit", "mace hit", "cobweb place"};

    // the verbose text for a combo alert, e.g. "80% of the last 10 stuns identical to the tick: swap-in 2t, ..."
    private static String comboVerbose(ComboRepeat co, int k, int of) {
        int[] g = co.sig[k];
        String kind = k == 0 ? "stuns" : k == 1 ? "stun slams" : "stun webs";
        return String.format("%.0f%% of the last %d %s identical to the tick: swap-in %s, swap away %s, %s %s",
                co.share[k] * 100, of, kind, ticks(g[0]), ticks(g[1]), COMBO_STEPS[k], ticks(g[2]));
    }

    private static String ticks(int t) {
        return t < 0 ? "none" : t + "t";
    }

    // the player's AimGrid state for /grimml status
    public String gridStatus(UUID id) {
        Grid gr = grids.get(id);
        return gr == null ? null : String.format("step %.5f score %.1f baseline %.1f%% legit rate %.1f%%",
                gr.live.step(), gr.live.score(), gr.live.baseline() * 100, gr.live.legitRate() * 100);
    }
}
