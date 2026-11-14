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

    public static int[] mapFeatures(java.util.List<String> names) {
        int[] idx = new int[names.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = Features.indexOf(names.get(i));
            if (idx[i] < 0) {
                throw new IllegalArgumentException("model wants feature '" + names.get(i) + "' which this plugin version does not compute; update the plugin");
            }
        }
        return idx;
    }
}
