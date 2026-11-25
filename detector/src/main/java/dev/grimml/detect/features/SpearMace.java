package dev.grimml.detect.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is the main feature extractor for the spear mace modules.
 * It takes the window and returns the features.
 * 
 * Its different to the normal extractor as it measures how fast the player is able to swap. And how consistently they are able to do it.
 */
public final class SpearMace {

    public static final String[] NAMES = {
            "swap_order_violations",
            "lunge_stabs", "lunge_swap_in_frac", "lunge_swap_stab_ms_mean", "lunge_swap_stab_ms_std",
            "lunge_back_ms_mean", "lunge_back_ms_std", "lunge_back_ticks_mode_frac", "lunge_interval_ms_mean",
            "lunge_interval_cv",
            "mace_hits", "mace_fall_mean", "mace_apex_ticks_mean", "mace_land_tick_frac", "mace_swap_in_frac",
            "mace_swap_hit_ms_mean", "mace_swap_hit_ms_std", "mace_back_ms_mean", "mace_back_ms_std",
            "mace_snap_deg_mean", "mace_snapback_frac", "mace_aim_err_mean",
            "stun_slams", "stun_gap_ms_mean", "stun_gap_ms_std", "stun_gap_ticks_mode_frac", "stun_axe_swap_hit_ms_mean",
            "stun_axe_to_mace_swap_ms_mean", "stun_block_frac",
    };

    public static final int AXE = 2, MACE = 3, SPEAR = 4, STAB = 8;
    static final double LUNGE_SWAP_IN_MS = 150.0, MACE_SWAP_IN_MS = 500.0, SWAP_BACK_MS = 1000.0,
            LUNGE_INTERVAL_MS = 2000.0, STUN_GAP_MS = 500.0, SNAP_DEG = 10.0;

    private static final int SWAP = 1, HIT = 2, STAB_ACT = 3;
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static { // mapping the names to the indices
        for (int i = 0; i < NAMES.length; i++) {
            INDEX.put(NAMES[i], i);
        }
    }

    // one events components
    private static final class Act {
        int k, ct, from, to, cat, tick, target;
        long n;
    }

    private final Window4 w;
    private final double[] row = new double[NAMES.length];
    private final List<Act> acts;

    private SpearMace(Window4 w) {
        this.w = w;
        java.util.Arrays.fill(row, Double.NaN);
        this.acts = actions(w);
    }

    /** Everything except the aim part (schema 3 and 4). */
    public static double[] features(Window4 w) {
        SpearMace s = new SpearMace(w);
        s.order();
        s.lunge();
        s.mace();
        s.stun();
        return s.row;
    }

    private void set(String name, double v) {
        row[INDEX.get(name)] = v;
    }

    static List<Act> actions(Window4 w) {
        List<Act> out = new ArrayList<>();
        for (int e = 0; e < w.m; e++) {
            int t = w.eType[e];
            Act a = new Act();
            a.n = w.eNanos[e];
            a.ct = w.eClientTick[e];
            a.tick = w.eTick[e];
            if (t == Window4.HOTBAR_SWAP) {
                if (w.eA[e] == 40) continue;
                a.k = SWAP;
                a.from = w.eC[e] & 0xFF;
                a.to = (w.eC[e] >> 8) & 0xFF;
            } else if (t == Window4.ATTACK) {
                int g = w.eG[e];
                if ((g & 0xFF) != 1 && !(g == 0 && w.eA[e] != 0)) continue;
                a.k = HIT;
                a.target = w.eB[e];
                a.cat = (w.eC[e] >> 8) & 0xFF;
            } else if (t == Window4.BREAK && w.eA[e] == STAB) {
                a.k = STAB_ACT;
                a.cat = SPEAR;
            } else {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private Act swapBefore(int i) {
        for (int j = i - 1; j >= 0; j--) if (acts.get(j).k == SWAP) return acts.get(j);
        return null;
    }

    private Act swapAfter(int i) {
        for (int j = i + 1; j < acts.size(); j++) if (acts.get(j).k == SWAP) return acts.get(j);
        return null;
    }

    private static double ms(Act a, Act b) {
        return (b.n - a.n) / 1e6;
    }

    private static double mean2(List<Double> x) {
        return x.size() >= 2 ? Schema4Features.mean(x) : Double.NaN;
    }

    private static double boolMean(List<Boolean> x) {
        int t = 0;
        for (boolean b : x) if (b) t++;
        return (double) t / x.size();
    }

    // share of values equal to the most common one
    static double modeFrac(List<Integer> x) {
        if (x.size() < 3) return Double.NaN;
        Map<Integer, Integer> c = new HashMap<>();
        for (int v : x) c.merge(v, 1, Integer::sum);
        int best = Integer.MAX_VALUE, bestN = -1;
        for (var en : c.entrySet()) {
            int v = en.getKey(), n = en.getValue();
            if (n > bestN || (n == bestN && v < best)) {
                best = v;
                bestN = n;
            }
        }
        return (double) bestN / x.size();
    }

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

    private boolean onGround(int k) {
        return (w.flags[k] & Window4.F_ON_GROUND) != 0;
    }

    // ---------------------------------------------------------------- families

    private void order() {
        boolean known = false;
        for (Act a : acts) if (a.ct > 0) known = true;
        if (!known) return;
        int n = 0, lastHitCt = -1;
        for (Act a : acts) {
            if (a.k == SWAP) {
                if (a.ct > 0 && a.ct == lastHitCt) n++;
            } else {
                lastHitCt = a.ct;
            }
        }
        set("swap_order_violations", n);
    }

    private void lunge() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < acts.size(); i++) if (acts.get(i).k == STAB_ACT) idx.add(i);
        set("lunge_stabs", idx.size());
        int swapped = 0;
        List<Double> swMs = new ArrayList<>(), backMs = new ArrayList<>(), gaps = new ArrayList<>();
        List<Integer> backTicks = new ArrayList<>();
        for (int i : idx) {
            Act a = acts.get(i);
            Act s = swapBefore(i);
            if (s != null && s.to == SPEAR && ms(s, a) <= LUNGE_SWAP_IN_MS) {
                swapped++;
                swMs.add(ms(s, a));
            }
            Act b = swapAfter(i);
            if (b != null && b.from == SPEAR && ms(a, b) <= SWAP_BACK_MS) {
                backMs.add(ms(a, b));
                if (b.ct > 0 && a.ct > 0) backTicks.add(b.ct - a.ct);
            }
        }
        for (int q = 1; q < idx.size(); q++) {
            double d = ms(acts.get(idx.get(q - 1)), acts.get(idx.get(q)));
            if (d <= LUNGE_INTERVAL_MS) gaps.add(d);
        }
        if (idx.size() >= 2) set("lunge_swap_in_frac", (double) swapped / idx.size());
        set("lunge_swap_stab_ms_mean", mean2(swMs));
        set("lunge_swap_stab_ms_std", Schema4Features.std2(swMs));
        set("lunge_back_ms_mean", mean2(backMs));
        set("lunge_back_ms_std", Schema4Features.std2(backMs));
        set("lunge_back_ticks_mode_frac", modeFrac(backTicks));
        if (gaps.size() >= 3) {
            double m = Schema4Features.mean(gaps);
            set("lunge_interval_ms_mean", m);
            if (m > 0) set("lunge_interval_cv", Schema4Features.std2(gaps) / m);
        }
    }

    private void mace() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < acts.size(); i++) {
            Act a = acts.get(i);
            if (a.k == HIT && a.cat == MACE) idx.add(i);
        }
        set("mace_hits", idx.size());
        if (idx.size() < 2) return;
        List<Double> falls = new ArrayList<>(), apex = new ArrayList<>(), swMs = new ArrayList<>(), backMs = new ArrayList<>();
        List<Boolean> land = new ArrayList<>();
        int swapped = 0;
        for (int i : idx) {
            Act a = acts.get(i);
            int k = frameIndexAt(a.tick);
            if (k >= 0) {
                int top = w.yMilli[k], ai = k;
                for (int j = k; j >= 0; j--) {
                    if (w.yMilli[j] > top) {
                        top = w.yMilli[j];
                        ai = j;
                    }
                    if (onGround(j)) break;
                }
                falls.add((top - w.yMilli[k]) / 1000.0);
                apex.add((double) (w.tick[k] - w.tick[ai]));
                land.add(!onGround(k) && k + 1 < w.n && onGround(k + 1));
            }
            Act s = swapBefore(i);
            if (s != null && s.to == MACE && ms(s, a) <= MACE_SWAP_IN_MS) {
                swapped++;
                swMs.add(ms(s, a));
            }
            Act b = swapAfter(i);
            if (b != null && b.from == MACE && ms(a, b) <= SWAP_BACK_MS) backMs.add(ms(a, b));
        }
        set("mace_fall_mean", mean2(falls));
        set("mace_apex_ticks_mean", mean2(apex));
        if (land.size() >= 2) set("mace_land_tick_frac", boolMean(land));
        set("mace_swap_in_frac", (double) swapped / idx.size());
        set("mace_swap_hit_ms_mean", mean2(swMs));
        set("mace_swap_hit_ms_std", Schema4Features.std2(swMs));
        set("mace_back_ms_mean", mean2(backMs));
        set("mace_back_ms_std", Schema4Features.std2(backMs));
    }

    private void stun() {
        List<Double> gaps = new ArrayList<>(), axeSw = new ArrayList<>(), toMace = new ArrayList<>();
        List<Integer> ticks = new ArrayList<>();
        List<Boolean> block = new ArrayList<>();
        for (int i = 0; i < acts.size(); i++) {
            Act a = acts.get(i);
            if (a.k != HIT || a.cat != AXE) continue;
            int nxt = -1;
            for (int j = i + 1; j < acts.size(); j++) {
                if (acts.get(j).k != SWAP) {
                    nxt = j;
                    break;
                }
            }
            if (nxt < 0) continue;
            Act m = acts.get(nxt);
            if (m.k != HIT || m.cat != MACE || m.target != a.target || ms(a, m) > STUN_GAP_MS) continue;
            gaps.add(ms(a, m));
            if (a.ct > 0 && m.ct > 0) ticks.add(m.ct - a.ct);
            Act s = swapBefore(i);
            if (s != null && s.to == AXE && ms(s, a) <= MACE_SWAP_IN_MS) axeSw.add(ms(s, a));
            for (int j = i + 1; j < nxt; j++) {
                if (acts.get(j).to == MACE) {
                    toMace.add(ms(a, acts.get(j)));
                    break;
                }
            }
            int k = frameIndexAt(a.tick) - 1;
            if (k >= 0 && w.hasOpp[k]) block.add((w.oppState[k] & Window4.OPP_BLOCKING) != 0);
        }
        set("stun_slams", gaps.size());
        set("stun_gap_ms_mean", mean2(gaps));
        set("stun_gap_ms_std", Schema4Features.std2(gaps));
        set("stun_gap_ticks_mode_frac", modeFrac(ticks));
        set("stun_axe_swap_hit_ms_mean", mean2(axeSw));
        set("stun_axe_to_mace_swap_ms_mean", mean2(toMace));
        if (block.size() >= 2) set("stun_block_frac", boolMean(block));
    }

    // ---------------------------------------------------------------- aim on the mace hit (schema 4)

    static double[] maceAim(Window4 w, boolean[] hasAim, double[] errYaw) {
        SpearMace s = new SpearMace(w);
        List<Double> snap = new ArrayList<>(), err = new ArrayList<>();
        List<Boolean> back = new ArrayList<>();
        for (Act a : s.acts) {
            if (a.k != HIT || a.cat != MACE) continue;
            int k = s.frameIndexAt(a.tick);
            if (k < 0) continue;
            if (hasAim[k]) err.add(Math.abs(errYaw[k]));
            if (k >= 1 && k + 1 < w.n && w.hasRot[k - 1] && w.hasRot[k] && w.hasRot[k + 1]) {
                double din = Schema4Features.wrap((double) w.yawExact[k] - (double) w.yawExact[k - 1]);
                double dout = Schema4Features.wrap((double) w.yawExact[k + 1] - (double) w.yawExact[k]);
                snap.add(Math.abs(din));
                back.add(Math.abs(din) > SNAP_DEG && Math.abs(dout) > SNAP_DEG && (din > 0) != (dout > 0));
            }
        }
        return new double[]{mean2(snap), back.size() >= 2 ? boolMean(back) : Double.NaN, mean2(err)};
    }
}
