package dev.grimml.inference;

import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.format.RecordTypes;

import java.util.Arrays;

// mirror of training/grimml_train/features/extract.py FEATURE_COLUMNS; parity is checked by FeatureParitySelfCheck and pytest
public final class FeatureExtractor {

    public static final String[] NAMES = {
            "yaw_vel_mean", "yaw_vel_std", "yaw_vel_abs_mean", "yaw_jerk_mean", "yaw_snap_rate", "gcd_grid",
            "pitch_vel_mean", "pitch_range", "speed_mean", "on_ground_frac", "attack_count", "cps",
            "click_gap_mean_ms", "click_gap_cv", "ping_ms", "jitter_ms", "frame_count",
            "retotem_ms_min", "jump_reset_rate", "hotbar_swap_count", "swap_attack_gap_ms_min"
    };

    static final double SNAP_YAW_CENTI = 1500.0;
    private static final double NAN = Double.NaN;

    private FeatureExtractor() {}

    public static double[] extract(CombatSnapshot s) {
        double[] out = new double[NAMES.length];
        Arrays.fill(out, NAN);
        int n = s.frameCount;
        if (n >= 2) {
            double[] dy = new double[n - 1];
            for (int i = 1; i < n; i++) {
                dy[i - 1] = (double) s.fYaw[i] - s.fYaw[i - 1];
            }
            out[0] = mean(dy);
            out[1] = std(dy);
            double[] abs = new double[dy.length];
            int snaps = 0;
            for (int i = 0; i < dy.length; i++) {
                abs[i] = Math.abs(dy[i]);
                if (abs[i] > SNAP_YAW_CENTI) {
                    snaps++;
                }
            }
            out[2] = mean(abs);
            out[5] = gcdGrid(abs);
            out[4] = (double) snaps / dy.length;
            if (dy.length >= 2) {
                double[] jerk = new double[dy.length - 1];
                for (int i = 1; i < dy.length; i++) {
                    jerk[i - 1] = Math.abs(dy[i] - dy[i - 1]);
                }
                out[3] = mean(jerk);
            }
            double[] dp = new double[n - 1];
            double pmin = s.fPitch[0];
            double pmax = s.fPitch[0];
            double[] speed = new double[n - 1];
            for (int i = 1; i < n; i++) {
                dp[i - 1] = (double) s.fPitch[i] - s.fPitch[i - 1];
                pmin = Math.min(pmin, s.fPitch[i]);
                pmax = Math.max(pmax, s.fPitch[i]);
                double dx = (double) s.fX[i] - s.fX[i - 1];
                double dz = (double) s.fZ[i] - s.fZ[i - 1];
                speed[i - 1] = Math.sqrt(dx * dx + dz * dz);
            }
            out[6] = mean(dp);
            out[7] = pmax - pmin;
            out[8] = mean(speed);
        }
        if (n > 0) {
            int ground = 0;
            for (int i = 0; i < n; i++) {
                if ((s.fFlags[i] & 1) != 0) {
                    ground++;
                }
            }
            out[9] = (double) ground / n;
        }
        attacks(s, out);
        out[14] = s.pingMs;
        out[15] = s.jitterMs;
        out[16] = n;
        macros(s, out);
        return out;
    }

    private static void attacks(CombatSnapshot s, double[] out) {
        int count = 0;
        for (int i = 0; i < s.eventCount; i++) {
            if (s.eType[i] == RecordTypes.ATTACK) {
                count++;
            }
        }
        out[10] = count;
        if (count < 2) {
            return;
        }
        long[] nanos = new long[count];
        int k = 0;
        for (int i = 0; i < s.eventCount; i++) {
            if (s.eType[i] == RecordTypes.ATTACK) {
                nanos[k++] = s.eNanos[i];
            }
        }
        Arrays.sort(nanos);
        double[] gaps = new double[count - 1];
        int g = 0;
        for (int i = 1; i < count; i++) {
            double gap = (double) (nanos[i] - nanos[i - 1]) / 1e6;
            if (gap > 0) {
                gaps[g++] = gap;
            }
        }
        if (g > 0) {
            double[] pos = Arrays.copyOf(gaps, g);
            double mu = mean(pos);
            out[12] = mu;
            out[13] = mu > 0 ? std(pos) / mu : NAN;
            double span = (double) (nanos[count - 1] - nanos[0]) / 1e9;
            if (span > 0) {
                out[11] = (count - 1) / span;
            }
        }
    }

    private static void macros(CombatSnapshot s, double[] out) {
        double retotem = Double.POSITIVE_INFINITY;
        int jr = 0;
        int jrHit = 0;
        int swaps = 0;
        for (int i = 0; i < s.eventCount; i++) {
            byte t = s.eType[i];
            if (t == RecordTypes.RETOTEM && s.eB[i] != RecordTypes.RETOTEM_FAILED) {
                retotem = Math.min(retotem, s.eA[i] / 1e6);
            } else if (t == RecordTypes.JUMP_RESET) {
                jr++;
                if (s.eB[i] >= 0 && s.eB[i] <= 2) {
                    jrHit++;
                }
            } else if (t == RecordTypes.HELD_SLOT) {
                swaps++;
            }
        }
        out[17] = Double.isInfinite(retotem) ? NAN : retotem;
        out[18] = jr > 0 ? (double) jrHit / jr : NAN;
        out[19] = swaps;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < s.eventCount; i++) {
            if (s.eType[i] != RecordTypes.ATTACK) {
                continue;
            }
            long latest = Long.MIN_VALUE;
            for (int j = 0; j < s.eventCount; j++) {
                if (s.eType[j] == RecordTypes.HELD_SLOT && s.eNanos[j] <= s.eNanos[i] && s.eNanos[j] > latest) {
                    latest = s.eNanos[j];
                }
            }
            if (latest != Long.MIN_VALUE) {
                best = Math.min(best, (s.eNanos[i] - latest) / 1e6);
            }
        }
        out[20] = Double.isInfinite(best) ? NAN : best;
    }

    static double gcdGrid(double[] abs) {
        int c = 0;
        for (double v : abs) {
            if (v > 1.0) {
                c++;
            }
        }
        if (c < 4) {
            return NAN;
        }
        double[] d = new double[c];
        int k = 0;
        for (double v : abs) {
            if (v > 1.0) {
                d[k++] = v;
            }
        }
        Arrays.sort(d);
        return c % 2 == 1 ? d[c / 2] : (d[c / 2 - 1] + d[c / 2]) / 2.0;
    }

    static double mean(double[] x) {
        if (x.length == 0) {
            return NAN;
        }
        double sum = 0.0;
        for (double v : x) {
            sum += v;
        }
        return sum / x.length;
    }

    static double std(double[] x) {
        if (x.length == 0) {
            return NAN;
        }
        double mu = mean(x);
        double acc = 0.0;
        for (double v : x) {
            acc += (v - mu) * (v - mu);
        }
        return Math.sqrt(acc / x.length);
    }
}
