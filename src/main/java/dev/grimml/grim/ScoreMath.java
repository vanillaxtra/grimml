package dev.grimml.grim;

public final class ScoreMath {

    private ScoreMath() {}

    public static double decay(double score, long dtNanos, double halfLifeSeconds) {
        if (score <= 0 || dtNanos <= 0 || halfLifeSeconds <= 0) {
            return Math.max(0, score);
        }
        double dt = dtNanos / 1_000_000_000.0;
        return score * Math.pow(0.5, dt / halfLifeSeconds);
    }

    public static double add(double score, CheckWeight weight) {
        if (weight == null || weight.weight <= 0) {
            return score;
        }
        return score + weight.weight * Math.max(0, weight.trust);
    }

    public static double confidence(double score, double scale) {
        if (score <= 0 || scale <= 0) {
            return 0;
        }
        double c = 1.0 - Math.exp(-score * scale);
        if (c < 0) {
            return 0;
        }
        if (c > 1) {
            return 1;
        }
        return c;
    }
}
