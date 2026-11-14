package dev.grimml.detect.model;

import dev.grimml.detect.features.Features;

/**
 * One cheat module's model plus how to turn its window scores into a fight verdict.
 * windowThreshold: score above which one 8 s window counts as flagged (5% of legit windows in training).
 * alertShare: a fight is flagged when at least this share of its windows is flagged, after minWindows windows.
 */
public record ModuleModel(
        String module,
        String checkName,
        String description,
        LgbmModel model,
        int[] featureIndex,
        double windowThreshold,
        double alertShare,
        int minWindows,
        boolean enabled
) {

    public double score(double[] features) {
        double[] x = new double[featureIndex.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = features[featureIndex[i]];
        }
        return model.predict(x);
    }

    /** Index into the combined vector: the 13 core features, then the 44 schema 4 features. */
    public static int combinedIndex(String name) {
        int i = Features.indexOf(name);
        if (i >= 0) {
            return i;
        }
        int j = dev.grimml.detect.features.Schema4Features.indexOf(name);
        return j < 0 ? -1 : Features.NAMES.length + j;
    }

    public boolean usesSchema4() {
        for (int i : featureIndex) {
            if (i >= Features.NAMES.length) {
                return true;
            }
        }
        return false;
    }

    public static int[] mapFeatures(java.util.List<String> names) {
        int[] idx = new int[names.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = combinedIndex(names.get(i));
            if (idx[i] < 0) {
                throw new IllegalArgumentException("model wants feature '" + names.get(i) + "' which this plugin version does not compute; update the plugin");
            }
        }
        return idx;
    }
}
