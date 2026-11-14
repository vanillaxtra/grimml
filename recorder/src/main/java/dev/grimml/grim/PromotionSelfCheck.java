package dev.grimml.grim;

public final class PromotionSelfCheck {

    public static void main(String[] args) {
        GrimWeights w = GrimWeights.empty();
        if (Promotion.ready(7.9, 3, 3, w)) {
            throw new AssertionError("below threshold");
        }
        if (Promotion.ready(8, 1, 1, w)) {
            throw new AssertionError("one check one session");
        }
        if (!Promotion.ready(8, 2, 1, w)) {
            throw new AssertionError("two checks");
        }
        if (!Promotion.ready(8, 1, 2, w)) {
            throw new AssertionError("same check two sessions");
        }
        System.out.println("promotion self-check passed");
    }
}
