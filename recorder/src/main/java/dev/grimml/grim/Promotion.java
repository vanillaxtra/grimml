package dev.grimml.grim;

public final class Promotion {

    private Promotion() {}

    public static boolean ready(double score, int distinctInWindow, int sameCheckSessions, GrimWeights w) {
        if (w == null || score < w.promoteThreshold) {
            return false;
        }
        return distinctInWindow >= w.promoteDistinctChecks || sameCheckSessions >= w.promoteSameCheckSessions;
    }
}
