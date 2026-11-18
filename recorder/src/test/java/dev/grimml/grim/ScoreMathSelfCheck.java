package dev.grimml.grim;

public final class ScoreMathSelfCheck {

    public static void main(String[] args) {
        double half = ScoreMath.decay(8, 1_800_000_000_000L, 1800);
        if (Math.abs(half - 4) > 0.01) {
            throw new AssertionError("half life " + half);
        }
        if (ScoreMath.decay(8, 0, 1800) != 8) {
            throw new AssertionError("zero dt");
        }
        CheckWeight w = new CheckWeight("Reach", 3, CheckCategory.COMBAT, 1);
        if (Math.abs(ScoreMath.add(1, w) - 4) > 1e-9) {
            throw new AssertionError("add");
        }
        if (ScoreMath.add(1, new CheckWeight("x", 0, CheckCategory.PACKET, 1)) != 1) {
            throw new AssertionError("zero weight");
        }
        double c = ScoreMath.confidence(8, 0.12);
        if (c <= 0 || c >= 1) {
            throw new AssertionError("confidence " + c);
        }
        System.out.println("score math self-check passed");
    }
}
