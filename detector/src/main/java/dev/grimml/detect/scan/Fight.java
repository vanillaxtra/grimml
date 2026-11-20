package dev.grimml.detect.scan;

/**
 * One player's fight for one ML check, judged the way training measures it: the share of a whole stretch of windows
 * that score above the window threshold, decided once per stretch.
 *
 * A stretch ends when it reaches blockWindows windows, or when the fight ends (no scored window for longer than the
 * fight gap). It is judged only if it has at least minWindows windows, then the counters restart. Re-testing the
 * share after every window, as GrimML did before 1.1.0, gives a long legit fight a new chance to cross the line every
 * 2 s; training never does that, so the alert line did not account for it.
 *
 * Early alert: a blatant cheater should not wait for the block to fill. After earlyMinWindows windows the stretch
 * alerts at once if even the low end of its flagged share's confidence interval (Wilson score, earlyZ standard
 * deviations) is at or above the alert line, so the share is very unlikely to fall back under it. That happens at most
 * once per stretch, and the stretch's own verdict is then dropped so one stretch never alerts twice. earlyZ 0 turns it
 * off.
 *
 * Not thread-safe: only the scan thread touches it.
 */
public final class Fight {

    /** A judged stretch: windows scored, how many passed the window threshold, and the mean score. */
    public record Verdict(int windows, int flagged, double meanScore, boolean alert) {
        public double share() {
            return windows == 0 ? 0.0 : (double) flagged / windows;
        }
    }

    private long lastSeenNanos;
    private int windows;
    private int flagged;
    private double scoreSum;
    private boolean alerted;

    /**
     * Adds one scored window. Returns the verdict when this window closes a stretch (the previous fight ended, or the
     * block is full) or alerts it early, else null. A verdict for an ended fight covers the windows before this one.
     */
    public Verdict add(long now, double score, double windowThreshold, double alertShare,
                       int blockWindows, int minWindows, long gapNs, double earlyZ, int earlyMinWindows) {
        Verdict ended = expire(now, alertShare, minWindows, gapNs);
        lastSeenNanos = now;
        windows++;
        scoreSum += score;
        if (score >= windowThreshold) {
            flagged++;
        }
        if (windows >= blockWindows) {
            return judge(alertShare, minWindows);
        }
        if (ended == null && earlyZ > 0 && !alerted && windows >= earlyMinWindows
                && wilsonLow(flagged, windows, earlyZ) >= alertShare) {
            alerted = true;
            return new Verdict(windows, flagged, scoreSum / windows, true);
        }
        return ended;
    }

    /** Judges and clears the stretch if the fight has ended (nothing scored for longer than gapNs), else null. */
    public Verdict expire(long now, double alertShare, int minWindows, long gapNs) {
        if (windows == 0 || now - lastSeenNanos <= gapNs) {
            return null;
        }
        return judge(alertShare, minWindows);
    }

    public int windows() {
        return windows;
    }

    /** Lower end of the Wilson score interval for flagged out of n at z standard deviations. */
    static double wilsonLow(int flagged, int n, double z) {
        double p = (double) flagged / n;
        double z2 = z * z;
        double centre = p + z2 / (2.0 * n);
        double spread = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return (centre - spread) / (1 + z2 / n);
    }

    private Verdict judge(double alertShare, int minWindows) {
        Verdict v = windows < minWindows || alerted ? null
                : new Verdict(windows, flagged, scoreSum / windows, (double) flagged / windows >= alertShare);
        alerted = false;
        windows = 0;
        flagged = 0;
        scoreSum = 0;
        return v;
    }
}
