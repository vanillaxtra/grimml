package dev.grimml.grim;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public final class GrimWeights {

    public final int version;
    public final double unknownWeight;
    public final CheckCategory unknownCategory;
    public final double unknownTrust;
    public final int maxPingMs;
    public final int maxJitterMs;
    public final double maxMspt;
    public final int settleSeconds;
    public final double halfLifeSeconds;
    public final double promoteThreshold;
    public final int promoteDistinctChecks;
    public final int promoteWindowSeconds;
    public final int promoteSameCheckSessions;
    public final double confidenceScale;
    public final Map<String, CheckWeight> checks;

    public GrimWeights(
            int version,
            double unknownWeight,
            CheckCategory unknownCategory,
            double unknownTrust,
            int maxPingMs,
            int maxJitterMs,
            double maxMspt,
            int settleSeconds,
            double halfLifeSeconds,
            double promoteThreshold,
            int promoteDistinctChecks,
            int promoteWindowSeconds,
            int promoteSameCheckSessions,
            double confidenceScale,
            Map<String, CheckWeight> checks
    ) {
        this.version = version;
        this.unknownWeight = unknownWeight;
        this.unknownCategory = unknownCategory;
        this.unknownTrust = unknownTrust;
        this.maxPingMs = maxPingMs;
        this.maxJitterMs = maxJitterMs;
        this.maxMspt = maxMspt;
        this.settleSeconds = settleSeconds;
        this.halfLifeSeconds = halfLifeSeconds;
        this.promoteThreshold = promoteThreshold;
        this.promoteDistinctChecks = promoteDistinctChecks;
        this.promoteWindowSeconds = promoteWindowSeconds;
        this.promoteSameCheckSessions = promoteSameCheckSessions;
        this.confidenceScale = confidenceScale;
        this.checks = checks;
    }

    public static GrimWeights empty() {
        return new GrimWeights(0, 0, CheckCategory.PACKET, 0.2, 180, 80, 40, 10,
                1800, 8, 2, 3600, 2, 0.12, Collections.emptyMap());
    }

    public CheckWeight of(String name) {
        if (name == null) {
            return unknown("");
        }
        CheckWeight exact = checks.get(name);
        if (exact != null) {
            return exact;
        }
        return checks.getOrDefault(name.toLowerCase(Locale.ROOT), unknown(name));
    }

    CheckWeight unknown(String name) {
        return new CheckWeight(name, unknownWeight, unknownCategory, unknownTrust);
    }
}
