package dev.grimml.detect.features;

import java.util.HashMap;
import java.util.Map;

/**
 * This is a feature that detects if the player is using a silent aim, knockback displacement or snap auras.
 * 
 * Silent aim, knockback displacement and snap auras turn onto the target on the hit tick and back right after while a real flick
 * moves towards the player over several ticks and keeps tracking.
 * 
 * TO make sure we dont false flag real players, we also check the return share which is how close you get to returning to your original position
 * as most cheat clients will return to your original position instantly while real players performing kb displacement like in mole will not.
 */
public final class Flick {

    public static final String[] NAMES = {"flick_one_tick_frac", "flick_return_frac", "flick_snap_frac"};

    static final double FLICK_MIN_DEG = 8.0, ONE_TICK_SHARE = 0.8, RETURN_SHARE = 0.6;
    static final int SPAN = 3;

    private Flick() {
    }

    public static double[] features(Window4 w) {
        double[] row = {Double.NaN, Double.NaN, Double.NaN};
        // last frame of each client tick 
        Map<Integer, Integer> at = new HashMap<>();
        for (int i = 0; i < w.n; i++) {
            if (w.clientTick[i] != 0) {
                at.put(w.clientTick[i], i);
            }
        }
        int n = 0, one = 0, ret = 0, snap = 0;
        double[] ys = new double[2 * SPAN + 1];
        for (int e = 0; e < w.m; e++) {
            if (w.eType[e] != Window4.ATTACK || w.eClientTick[e] == 0) {
                continue;
            }
            int ct = w.eClientTick[e];
            boolean full = true;
            for (int k = -SPAN; k <= SPAN && full; k++) {
                Integer i = at.get(ct + k);
                if (i == null || !w.hasRot[i]) {
                    full = false;
                } else {
                    ys[k + SPAN] = w.yawExact[i];
                }
            }
            if (!full) {
                continue;
            }
            n++;
            double into = Schema4Features.wrap(ys[SPAN] - ys[0]);
            if (Math.abs(into) < FLICK_MIN_DEG) {
                continue;
            }
            double d0 = Schema4Features.wrap(ys[SPAN] - ys[SPAN - 1]);
            double back = Schema4Features.wrap(ys[2 * SPAN] - ys[SPAN]);
            boolean o = Math.abs(d0) > ONE_TICK_SHARE * Math.abs(into);
            boolean r = -back / into > RETURN_SHARE;
            if (o) one++;
            if (r) ret++;
            if (o && r) snap++;
        }
        if (n > 0) {
            row[0] = (double) one / n;
            row[1] = (double) ret / n;
            row[2] = (double) snap / n;
        }
        return row;
    }
}
