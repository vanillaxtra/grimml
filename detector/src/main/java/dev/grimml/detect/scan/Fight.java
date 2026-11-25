package dev.grimml.detect.scan;

public final class Fight {

    // a verdict for a fight
    public record Verdict(int windows, int flagged, double meanScore, boolean alert) {
        // returns the share of flagged windows
        public double share() {
            return windows == 0 ? 0.0 : (double) flagged / windows;
        }
    }

    private long lastSeenNanos;
    private int windows;
    private int flagged;
    private double scoreSum;
    private boolean alerted;

    // adds one scored window and returns the verdict when this window closes or alerts it early
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

    // judges and clears the stretch if the fight has ended (nothing scored for longer than gapNs), else null.
    public Verdict expire(long now, double alertShare, int minWindows, long gapNs) {
        if (windows == 0 || now - lastSeenNanos <= gapNs) {
            return null;
        }
        return judge(alertShare, minWindows);
    }

    // returns the number of windows
    public int windows() {
        return windows;
    }

    // returns the lower end of the Wilson score interval for flagged out of n at z standard deviations.
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
