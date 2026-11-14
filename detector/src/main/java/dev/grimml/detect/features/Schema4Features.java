package dev.grimml.detect.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of training/grimml_train/features/schema4.py. Same names, same order, same numbers:
 * ParitySelfCheck compares both on real schema 4 windows. Change both together.
 */
public final class Schema4Features {

    public static final String[] NAMES = {
            "aim_err_yaw_abs_mean", "aim_err_yaw_abs_median", "aim_err_pitch_abs_mean", "aim_pull_share", "aim_pull_corr",
            "aim_track_lag_ticks", "aim_rot_step", "aim_offgrid_frac",
            "trig_on_target_frac", "trig_entry_to_click_ticks_mean", "trig_entry_to_click_ticks_std",
            "trig_cooldown_mean", "trig_cooldown_std", "trig_click_in_hurt_frac",
            "bt_rtt_mean", "bt_rtt_std", "bt_pong_burst_frac", "bt_rtt_near_minus_far", "bt_stale_hit_frac",
            "bt_implied_lag_ticks_mean", "bt_gap_p95_ms", "bt_burst_mean",
            "jr_knockbacks", "jr_reset_rate", "jr_offset_mean", "jr_offset_std", "jr_offset_zero_frac",
            "wtap_rate", "wtap_reaction_ms_mean", "wtap_reaction_ms_std", "wtap_hold_ms_mean", "wtap_hold_ms_std",
            "wtap_same_tick_frac",
            "click_gap_kurtosis", "click_gap_entropy", "click_double_frac",
            "crystal_breaks", "crystal_break_ms_mean", "crystal_break_ms_std", "anchor_clicks", "anchor_gap_ms_std",
            "shield_axe_swaps", "shield_axe_reaction_ms_mean", "shield_axe_reaction_ms_std",
    };
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {
        for (int i = 0; i < NAMES.length; i++) {
            INDEX.put(NAMES[i], i);
        }
    }

    private static final double EYE = 1.62, EYE_SNEAK = 1.27, HALF_WIDTH = 0.3;
    private static final int AXE = 2;
    static final double MIN_MOUSE_STEP = 0.2 * 0.2 * 0.2 * 8 * 0.15;

    private Schema4Features() {
    }

    public static int indexOf(String name) {
        Integer i = INDEX.get(name);
        return i == null ? -1 : i;
    }

    private static final class Aim {
        double errYaw, errPitch, targetYaw, dist, halfAngle;
    }

    private double[] row;
    private Window4 w;
    private Aim[] aim;

    public static double[] compute(Window4 w, boolean schema4) {
        Schema4Features f = new Schema4Features();
        f.row = new double[NAMES.length];
        Arrays.fill(f.row, Double.NaN);
        f.w = w;
        f.click();
        if (!schema4) {
            return f.row;
        }
        f.aim = f.aimTrack();
        f.aimFeatures();
        f.trigger();
        f.backtrack();
        f.jumpReset();
        f.wtap();
        f.crystalAnchor();
        f.shield();
        return f.row;
    }

    private void set(String name, double v) {
        row[INDEX.get(name)] = v;
    }

    // ---------------------------------------------------------------- numpy-like helpers

    static double wrap(double deg) {
        double m = (deg + 180.0) % 360.0;
        if (m < 0) {
            m += 360.0;
        }
        return m - 180.0;
    }

    static double mean(double[] x, int n) {
        if (n == 0) return Double.NaN;
        double s = 0;
        for (int i = 0; i < n; i++) s += x[i];
        return s / n;
    }

    static double mean(List<Double> x) {
        if (x.isEmpty()) return Double.NaN;
        double s = 0;
        for (double v : x) s += v;
        return s / x.size();
    }

    static double pstd(double[] x, int n) {
        double mu = mean(x, n);
        double s = 0;
        for (int i = 0; i < n; i++) {
            double e = x[i] - mu;
            s += e * e;
        }
        return Math.sqrt(s / n);
    }

    /** _std: population std, NaN below 2 values. */
    static double std2(List<Double> x) {
        if (x.size() < 2) return Double.NaN;
        return pstd(toArr(x), x.size());
    }

    static double[] toArr(List<Double> x) {
        double[] a = new double[x.size()];
        for (int i = 0; i < a.length; i++) a[i] = x.get(i);
        return a;
    }

    static double median(double[] x) {
        double[] s = x.clone();
        Arrays.sort(s);
        int h = s.length / 2;
        return s.length % 2 == 1 ? s[h] : (s[h - 1] + s[h]) / 2.0;
    }

    /** numpy percentile, linear interpolation. */
    static double percentile(double[] x, double q) {
        double[] s = x.clone();
        Arrays.sort(s);
        double pos = (s.length - 1) * q / 100.0;
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, s.length - 1);
        double frac = pos - lo;
        return s[lo] + (s[hi] - s[lo]) * frac;
    }

    static double corr(double[] a, double[] b, int n) {
        double ma = mean(a, n), mb = mean(b, n);
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            sab += x * y;
            saa += x * x;
            sbb += y * y;
        }
        double c = sab / Math.sqrt(saa * sbb);
        return Math.max(-1.0, Math.min(1.0, c));
    }

    // ---------------------------------------------------------------- frames

    private int frameIndexAt(int tick) {
        int lo = 0, hi = w.n - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (w.tick[mid] <= tick) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private Aim[] aimTrack() {
        Aim[] out = new Aim[w.n];
        for (int i = 0; i < w.n; i++) {
            if (!w.hasOpp[i] || !w.hasRot[i]) {
                continue;
            }
            double dx = w.oppDx[i] / 1000.0;
            double dz = w.oppDz[i] / 1000.0;
            double eye = (w.flags[i] & Window4.F_SNEAK) != 0 ? EYE_SNEAK : EYE;
            double dy = w.oppDy[i] / 1000.0 + 0.9 - eye;
            double horiz = Math.hypot(dx, dz);
            double ty = Math.toDegrees(Math.atan2(-dx, dz));
            double tp = -Math.toDegrees(Math.atan2(dy, horiz));
            Aim a = new Aim();
            a.errYaw = wrap((double) w.yawExact[i] - ty);
            a.errPitch = (double) w.pitchExact[i] - tp;
            a.targetYaw = ty;
            a.dist = Math.sqrt(horiz * horiz + dy * dy);
            a.halfAngle = Math.toDegrees(Math.atan2(HALF_WIDTH, Math.max(a.dist, 0.1)));
            out[i] = a;
        }
        return out;
    }

    // ---------------------------------------------------------------- aim

    private void aimFeatures() {
        List<Double> ey = new ArrayList<>();
        List<Double> ep = new ArrayList<>();
        for (Aim a : aim) {
            if (a != null) {
                ey.add(Math.abs(a.errYaw));
                ep.add(Math.abs(a.errPitch));
            }
        }
        if (ey.size() < 10) {
            return;
        }
        set("aim_err_yaw_abs_mean", mean(ey));
        set("aim_err_yaw_abs_median", median(toArr(ey)));
        set("aim_err_pitch_abs_mean", mean(ep));

        int toward = 0, moves = 0;
        List<Double> dList = new ArrayList<>(), eList = new ArrayList<>(), tyD = new ArrayList<>(), pyD = new ArrayList<>();
        for (int i = 1; i < w.n; i++) {
            Aim a0 = aim[i - 1], a1 = aim[i];
            if (a0 == null || a1 == null) {
                continue;
            }
            double d = wrap((double) w.yawExact[i] - (double) w.yawExact[i - 1]);
            if (Math.abs(d) > 0.005) {
                moves++;
                if (Math.signum(d) == -Math.signum(a0.errYaw)) {
                    toward++;
                }
                dList.add(d);
                eList.add(-a0.errYaw);
            }
            tyD.add(wrap(a1.targetYaw - a0.targetYaw));
            pyD.add(d);
        }
        if (moves >= 8) {
            set("aim_pull_share", (double) toward / moves);
            double[] da = toArr(dList), ea = toArr(eList);
            if (pstd(da, da.length) > 0 && pstd(ea, ea.length) > 0) {
                set("aim_pull_corr", corr(da, ea, da.length));
            }
        }
        double[] ta = toArr(tyD), pa = toArr(pyD);
        if (ta.length >= 20 && pstd(ta, ta.length) > 0 && pstd(pa, pa.length) > 0) {
            int best = 0;
            double bestC = -2.0;
            for (int lag = 0; lag < 7; lag++) {
                int len = ta.length - lag;
                double[] a = Arrays.copyOfRange(ta, 0, len);
                double[] b = Arrays.copyOfRange(pa, lag, lag + len);
                if (len > 5 && pstd(a, len) > 0 && pstd(b, len) > 0) {
                    double c = corr(a, b, len);
                    if (c > bestC) {
                        best = lag;
                        bestC = c;
                    }
                }
            }
            set("aim_track_lag_ticks", best);
        }

        List<Double> steps = new ArrayList<>();
        for (int i = 1; i < w.n; i++) {
            if (w.hasRot[i] && w.hasRot[i - 1]) {
                double s = Math.abs((double) w.yawExact[i] - (double) w.yawExact[i - 1]);
                if (s > 1e-4 && s < 30.0) {
                    steps.add(s);
                }
            }
        }
        if (steps.size() >= 10) {
            double[] st = toArr(steps);
            double[] small = st.clone();
            Arrays.sort(small);
            small = Arrays.copyOf(small, Math.min(small.length, 12));
            double bestStep = Double.NaN, bestOn = 0.0;
            for (double d : small) {
                for (int k = 1; k <= 4; k++) {
                    double s = d / k;
                    if (s < MIN_MOUSE_STEP) {
                        continue;
                    }
                    int on = 0;
                    for (double v : st) {
                        double r = v / s;
                        double rr = Math.rint(r);
                        if (Math.abs(r - rr) <= 0.1 && rr >= 1) {
                            on++;
                        }
                    }
                    double frac = (double) on / st.length;
                    if (frac > bestOn + 1e-9) {
                        bestStep = s;
                        bestOn = frac;
                    }
                }
            }
            set("aim_rot_step", bestStep);
            set("aim_offgrid_frac", 1.0 - bestOn);
        }
    }

    // ---------------------------------------------------------------- triggerbot

    private boolean isPlayerAttack(int e) {
        return w.eType[e] == Window4.ATTACK && (w.eG[e] & 0xFF) == 1;
    }

    private void trigger() {
        List<Integer> attacks = new ArrayList<>();
        for (int e = 0; e < w.m; e++) if (isPlayerAttack(e)) attacks.add(e);
        if (attacks.size() < 3) {
            return;
        }
        List<Double> on = new ArrayList<>(), entry = new ArrayList<>(), cooldown = new ArrayList<>(), inHurt = new ArrayList<>();
        for (int e : attacks) {
            cooldown.add((double) (w.eG[e] >> 8));
            int k = frameIndexAt(w.eTick[e]);
            if (k < 0 || aim[k] == null) {
                continue;
            }
            Aim a = aim[k];
            boolean isOn = Math.abs(a.errYaw) <= a.halfAngle;
            on.add(isOn ? 1.0 : 0.0);
            if (w.hasOpp[k] && w.oppHurtAgo[k] >= 0) {
                inHurt.add(w.oppHurtAgo[k] < 500 ? 1.0 : 0.0);
            }
            if (isOn) {
                int j = k;
                while (j > 0 && aim[j - 1] != null && Math.abs(aim[j - 1].errYaw) <= aim[j - 1].halfAngle) {
                    j--;
                }
                entry.add((double) (k - j));
            }
        }
        if (!on.isEmpty()) set("trig_on_target_frac", mean(on));
        if (!entry.isEmpty()) {
            set("trig_entry_to_click_ticks_mean", mean(entry));
            set("trig_entry_to_click_ticks_std", std2(entry));
        }
        set("trig_cooldown_mean", mean(cooldown));
        set("trig_cooldown_std", std2(cooldown));
        if (!inHurt.isEmpty()) set("trig_click_in_hurt_frac", mean(inHurt));
    }

    // ---------------------------------------------------------------- backtrack

    /** (reply nanos, round trip ms) sorted, like _rtts. */
    private List<double[]> rtts() {
        Map<Long, Long> sent = new HashMap<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.RTT_SENT && w.eB[e] == 1) {
                sent.put(w.eA[e], w.eNanos[e]);
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.RTT && w.eC[e] == 1) {
                Long s = sent.get(w.eA[e]);
                if (s != null) {
                    out.add(new double[]{w.eNanos[e], (w.eNanos[e] - s) / 1e6});
                }
            }
        }
        out.sort(Comparator.<double[]>comparingDouble(r -> r[0]).thenComparingDouble(r -> r[1]));
        return out;
    }

    private int nearestFrameByNanos(long nanos) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int e = 0; e < w.m; e++) {
            long d = Math.abs(w.eNanos[e] - nanos);
            if (best < 0 || d < bestD) {
                best = e;
                bestD = d;
            }
        }
        if (best < 0) {
            return -1;
        }
        return frameIndexAt(w.eTick[best]);
    }

    private void backtrack() {
        List<double[]> rt = rtts();
        if (rt.size() >= 5) {
            double[] ms = new double[rt.size()];
            for (int i = 0; i < ms.length; i++) ms[i] = rt.get(i)[1];
            set("bt_rtt_mean", mean(ms, ms.length));
            set("bt_rtt_std", pstd(ms, ms.length));
            int bursts = 0;
            for (int i = 1; i < rt.size(); i++) {
                if (((long) rt.get(i)[0] - (long) rt.get(i - 1)[0]) / 1e6 < 2.0) bursts++;
            }
            set("bt_pong_burst_frac", rt.size() > 1 ? (double) bursts / (rt.size() - 1) : Double.NaN);
            List<Double> near = new ArrayList<>(), far = new ArrayList<>();
            for (double[] r : rt) {
                int k = nearestFrameByNanos((long) r[0]);
                if (k >= 0 && aim[k] != null) {
                    (aim[k].dist <= 3.5 ? near : far).add(r[1]);
                }
            }
            if (near.size() >= 3 && far.size() >= 3) {
                set("bt_rtt_near_minus_far", mean(near) - mean(far));
            }
        }

        List<Double> stale = new ArrayList<>(), lags = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (!isPlayerAttack(e)) continue;
            int k = frameIndexAt(w.eTick[e]);
            if (k < 0 || aim[k] == null) continue;
            int bestJ = 0;
            double bestErr = Double.POSITIVE_INFINITY;
            for (int j = 0; j < 8; j++) {
                double err = (k - j >= 0 && aim[k - j] != null) ? Math.abs(aim[k - j].errYaw) : 1e9;
                if (err < bestErr) {
                    bestErr = err;
                    bestJ = j;
                }
            }
            lags.add((double) bestJ);
            boolean wasInReach = false;
            for (int j = 1; j < 8; j++) {
                if (k - j >= 0 && aim[k - j] != null && aim[k - j].dist <= 3.0) {
                    wasInReach = true;
                    break;
                }
            }
            stale.add(aim[k].dist > 3.3 && wasInReach ? 1.0 : 0.0);
        }
        if (lags.size() >= 3) {
            set("bt_implied_lag_ticks_mean", mean(lags));
            set("bt_stale_hit_frac", mean(stale));
        }

        if (w.n >= 20) {
            double[] gaps = new double[w.n];
            double[] bursts = new double[w.n];
            for (int i = 0; i < w.n; i++) {
                gaps[i] = w.maxGap[i];
                bursts[i] = w.burst[i];
            }
            set("bt_gap_p95_ms", percentile(gaps, 95));
            set("bt_burst_mean", mean(bursts, w.n));
        }
    }

    // ---------------------------------------------------------------- jump reset

    private void jumpReset() {
        List<Integer> kbs = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.KNOCKBACK && Math.abs(w.eA[e]) + Math.abs((long) w.eC[e]) > 400) kbs.add(e);
        }
        if (kbs.isEmpty()) {
            return;
        }
        List<long[]> sent = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.RTT_SENT && w.eB[e] == 1) sent.add(new long[]{w.eNanos[e], w.eA[e]});
        }
        sent.sort(Comparator.<long[]>comparingLong(r -> r[0]).thenComparingLong(r -> r[1]));
        Map<Long, Integer> replies = new HashMap<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.RTT && w.eC[e] == 1) replies.put(w.eA[e], e);
        }
        List<Integer> inputs = new ArrayList<>();
        for (int e = 0; e < w.m; e++) if (w.eType[e] == Window4.INPUT) inputs.add(e);
        inputs.sort(Comparator.comparingLong(e -> w.eNanos[e]));
        List<Integer> presses = new ArrayList<>();
        long prev = 0;
        for (int e : inputs) {
            if ((w.eA[e] & 16) != 0 && (prev & 16) == 0) {
                presses.add(w.eClientTick[e]);
            }
            prev = w.eA[e];
        }
        List<Double> offsets = new ArrayList<>();
        int considered = 0;
        for (int kb : kbs) {
            Long after = null;
            for (long[] s : sent) {
                if (s[0] >= w.eNanos[kb]) {
                    after = s[1];
                    break;
                }
            }
            Integer rep = after == null ? null : replies.get(after);
            if (rep == null) continue;
            int k = frameIndexAt(w.eTick[kb]);
            if (k < 0 || (w.flags[k] & Window4.F_ON_GROUND) == 0) continue;
            considered++;
            int arrive = w.eClientTick[rep];
            Integer bestOff = null;
            for (int p : presses) {
                int o = p - arrive;
                if (o >= -2 && o <= 4 && (bestOff == null || Math.abs(o) < Math.abs(bestOff))) {
                    bestOff = o;
                }
            }
            if (bestOff != null) offsets.add((double) bestOff);
        }
        set("jr_knockbacks", considered);
        if (considered >= 2) {
            int good = 0;
            for (double o : offsets) if (o >= -1 && o <= 2) good++;
            set("jr_reset_rate", (double) good / considered);
        }
        if (offsets.size() >= 2) {
            set("jr_offset_mean", mean(offsets));
            set("jr_offset_std", std2(offsets));
            int zero = 0;
            for (double o : offsets) if (o == 0) zero++;
            set("jr_offset_zero_frac", (double) zero / offsets.size());
        }
    }

    // ---------------------------------------------------------------- W-tap

    private void wtap() {
        List<Integer> ev = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            int t = w.eType[e];
            if (t == Window4.ATTACK || t == Window4.INPUT || t == Window4.MOVE_STATE) ev.add(e);
        }
        ev.sort(Comparator.comparingLong(e -> w.eNanos[e]));
        List<Integer> attacks = new ArrayList<>();
        for (int e : ev) if (isPlayerAttack(e)) attacks.add(e);
        if (attacks.size() < 3) {
            return;
        }
        int taps = 0;
        List<Double> react = new ArrayList<>(), hold = new ArrayList<>(), same = new ArrayList<>();
        for (int a : attacks) {
            int rel = -1, release = -1;
            Boolean wOn = null;
            for (int e : ev) {
                int t = w.eType[e];
                if (w.eNanos[e] <= w.eNanos[a]) {
                    if (t == Window4.INPUT) wOn = (w.eA[e] & 1) != 0;
                    continue;
                }
                if (w.eNanos[e] - w.eNanos[a] > 500e6) break;
                if (rel < 0) {
                    if ((t == Window4.INPUT && Boolean.TRUE.equals(wOn) && (w.eA[e] & 1) == 0)
                            || (t == Window4.MOVE_STATE && w.eA[e] == 2)) {
                        rel = e;
                    }
                } else if ((t == Window4.INPUT && (w.eA[e] & 1) != 0) || (t == Window4.MOVE_STATE && w.eA[e] == 1)) {
                    release = e;
                    break;
                }
                if (t == Window4.INPUT) wOn = (w.eA[e] & 1) != 0;
            }
            if (rel < 0) continue;
            taps++;
            react.add((w.eNanos[rel] - w.eNanos[a]) / 1e6);
            same.add(w.eClientTick[rel] == w.eClientTick[a] ? 1.0 : 0.0);
            if (release >= 0) hold.add((w.eNanos[release] - w.eNanos[rel]) / 1e6);
        }
        set("wtap_rate", (double) taps / attacks.size());
        if (!react.isEmpty()) {
            set("wtap_reaction_ms_mean", mean(react));
            set("wtap_reaction_ms_std", std2(react));
        }
        if (!hold.isEmpty()) {
            set("wtap_hold_ms_mean", mean(hold));
            set("wtap_hold_ms_std", std2(hold));
        }
        if (!same.isEmpty()) set("wtap_same_tick_frac", mean(same));
    }

    // ---------------------------------------------------------------- clicks, crystals, anchors, shields

    private void click() {
        List<Long> t = new ArrayList<>();
        for (int e = 0; e < w.m; e++) if (w.eType[e] == Window4.SWING) t.add(w.eNanos[e]);
        t.sort(null);
        List<Double> g = new ArrayList<>();
        for (int i = 1; i < t.size(); i++) {
            double gap = (t.get(i) - t.get(i - 1)) / 1e6;
            if (gap > 0 && gap < 1000) g.add(gap);
        }
        if (g.size() < 8) {
            return;
        }
        double[] gaps = toArr(g);
        double mu = mean(gaps, gaps.length);
        double sd = pstd(gaps, gaps.length);
        if (sd > 0) {
            double s = 0;
            for (double v : gaps) {
                double z = (v - mu) / sd;
                s += z * z * z * z;
            }
            set("click_gap_kurtosis", s / gaps.length - 3.0);
        }
        int[] hist = new int[100];
        for (double v : gaps) {
            int idx = (int) Math.floor(v * 0.1);
            if (idx > 0 && v < idx * 10.0) idx--;
            if (idx < 99 && v >= (idx + 1) * 10.0) idx++;
            idx = Math.max(0, Math.min(99, idx));
            hist[idx]++;
        }
        double ent = 0;
        for (int c : hist) {
            if (c > 0) {
                double p = (double) c / gaps.length;
                ent -= p * (Math.log(p) / Math.log(2));
            }
        }
        set("click_gap_entropy", ent);
        int dbl = 0;
        for (double v : gaps) if (v < 50) dbl++;
        set("click_double_frac", (double) dbl / gaps.length);
    }

    private void crystalAnchor() {
        Map<Long, Long> spawns = new HashMap<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.ENTITY_SPAWN && w.eB[e] == 1) spawns.put(w.eA[e], w.eNanos[e]);
        }
        List<Double> breaks = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.ATTACK && (w.eG[e] & 0xFF) == 2) {
                Long s = spawns.get((long) w.eB[e]);
                if (s != null) breaks.add((w.eNanos[e] - s) / 1e6);
            }
        }
        set("crystal_breaks", breaks.size());
        if (breaks.size() >= 2) {
            set("crystal_break_ms_mean", mean(breaks));
            set("crystal_break_ms_std", std2(breaks));
        }
        List<Long> anchors = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] == Window4.BLOCK_PLACE && (w.eG[e] & 0xFF) == 1) anchors.add(w.eNanos[e]);
        }
        anchors.sort(null);
        set("anchor_clicks", anchors.size());
        if (anchors.size() >= 3) {
            List<Double> d = new ArrayList<>();
            for (int i = 1; i < anchors.size(); i++) d.add((anchors.get(i) - anchors.get(i - 1)) / 1e6);
            set("anchor_gap_ms_std", std2(d));
        }
    }

    private void shield() {
        List<Double> rx = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] != Window4.HOTBAR_SWAP || ((w.eC[e] >> 8) & 0xFF) != AXE) continue;
            int k = frameIndexAt(w.eTick[e]);
            if (k < 0 || !w.hasOpp[k]) continue;
            if ((w.oppState[k] & Window4.OPP_BLOCKING) != 0 && w.oppBlockAgo[k] >= 0) {
                rx.add((double) w.oppBlockAgo[k]);
            }
        }
        set("shield_axe_swaps", rx.size());
        if (rx.size() >= 2) {
            set("shield_axe_reaction_ms_mean", mean(rx));
            set("shield_axe_reaction_ms_std", std2(rx));
        }
    }
}
