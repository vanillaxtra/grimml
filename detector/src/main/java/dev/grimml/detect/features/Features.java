package dev.grimml.detect.features;

import java.util.Arrays;

/**
 * Java port of training/grimml_train/features/extract.py (features_from_decoded), the 13 core features.
 * Any change there must be made here too; ParitySelfCheck compares both on real windows.
 */
public final class Features {

    public static final String[] NAMES = {
            "yaw_vel_mean", "yaw_vel_std", "yaw_vel_abs_mean", "yaw_jerk_mean", "yaw_snap_rate", "gcd_grid",
            "pitch_vel_mean", "pitch_range", "speed_mean", "on_ground_frac", "cps", "click_gap_mean_ms", "click_gap_cv",
    };
    private static final double SNAP_YAW_CENTI = 1500;

    private Features() {
    }

    public static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param yaw/pitch  centidegrees per frame, x/z millimetres per frame, flags per frame (bit 0 = on ground)
     * @param n          number of frames used from the arrays
     * @param attacks    attack packet times in nanoseconds, m used
     */
    public static double[] compute(int[] yaw, int[] pitch, int[] x, int[] z, int[] flags, int n, long[] attacks, int m) {
        double[] out = new double[NAMES.length];
        Arrays.fill(out, Double.NaN);

        if (n >= 2) {
            double[] dy = new double[n - 1];
            double[] ady = new double[n - 1];
            for (int i = 1; i < n; i++) {
                dy[i - 1] = (double) yaw[i] - yaw[i - 1];
                ady[i - 1] = Math.abs(dy[i - 1]);
            }
            double mu = mean(dy);
            out[0] = mu;
            out[1] = std(dy, mu);
            out[2] = mean(ady);
            out[5] = gcdGrid(ady);
            int snaps = 0;
            for (double a : ady) {
                if (a > SNAP_YAW_CENTI) {
                    snaps++;
                }
            }
            out[4] = (double) snaps / ady.length;
            if (dy.length >= 2) {
                double[] jerk = new double[dy.length - 1];
                for (int i = 1; i < dy.length; i++) {
                    jerk[i - 1] = Math.abs(dy[i] - dy[i - 1]);
                }
                out[3] = mean(jerk);
            }

            double[] dp = new double[n - 1];
            int pmin = pitch[0];
            int pmax = pitch[0];
            for (int i = 1; i < n; i++) {
                dp[i - 1] = (double) pitch[i] - pitch[i - 1];
                pmin = Math.min(pmin, pitch[i]);
                pmax = Math.max(pmax, pitch[i]);
            }
            out[6] = mean(dp);
            out[7] = (double) pmax - pmin;

            double[] speed = new double[n - 1];
            for (int i = 1; i < n; i++) {
                double ddx = (double) x[i] - x[i - 1];
                double ddz = (double) z[i] - z[i - 1];
                speed[i - 1] = Math.sqrt(ddx * ddx + ddz * ddz);
            }
            out[8] = mean(speed);
        }
        if (n >= 1) {
            int grounded = 0;
            for (int i = 0; i < n; i++) {
                if ((flags[i] & 1) != 0) {
                    grounded++;
                }
            }
            out[9] = (double) grounded / n;
        }

        if (m >= 2) {
            long[] t = Arrays.copyOf(attacks, m);
            Arrays.sort(t);
            double[] gaps = new double[m - 1];
            int g = 0;
            for (int i = 1; i < m; i++) {
                double gap = (t[i] - t[i - 1]) / 1e6;
                if (gap > 0) {
                    gaps[g++] = gap;
                }
            }
            if (g > 0) {
                double[] gg = Arrays.copyOf(gaps, g);
                double mu = mean(gg);
                out[11] = mu;
                out[12] = mu > 0 ? std(gg, mu) / mu : Double.NaN;
                double span = (t[m - 1] - t[0]) / 1e9;
                if (span > 0) {
                    out[10] = (m - 1) / span;
                }
            }
        }
        return out;
    }

    private static double mean(double[] v) {
        double s = 0.0;
        for (double d : v) {
            s += d;
        }
        return s / v.length;
    }

    // population std (numpy default ddof=0)
    private static double std(double[] v, double mu) {
        double s = 0.0;
        for (double d : v) {
            double e = d - mu;
            s += e * e;
        }
        return Math.sqrt(s / v.length);
    }

    // median of |yaw steps| above 1 centidegree, needs at least 4
    private static double gcdGrid(double[] abs) {
        double[] d = Arrays.stream(abs).filter(a -> a > 1.0).sorted().toArray();
        if (d.length < 4) {
            return Double.NaN;
        }
        int h = d.length / 2;
        return d.length % 2 == 1 ? d[h] : (d[h - 1] + d[h]) / 2.0;
    }
}
