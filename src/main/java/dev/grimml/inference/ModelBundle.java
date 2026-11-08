package dev.grimml.inference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

// grimml-train export output: model.txt + model_meta.json (feature order, scaler, threshold)
public final class ModelBundle {

    public final LgbmModel model;
    public final double threshold;
    private final int[] columns;
    private final double[] mean;
    private final double[] scale;

    private ModelBundle(LgbmModel model, double threshold, int[] columns, double[] mean, double[] scale) {
        this.model = model;
        this.threshold = threshold;
        this.columns = columns;
        this.mean = mean;
        this.scale = scale;
    }

    public static ModelBundle load(Path dir) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("model_meta.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray names = meta.getAsJsonArray("feature_names");
        List<String> known = Arrays.asList(FeatureExtractor.NAMES);
        int[] cols = new int[names.size()];
        for (int i = 0; i < cols.length; i++) {
            String n = names.get(i).getAsString();
            cols[i] = known.indexOf(n);
            if (cols[i] < 0) {
                throw new IOException("model needs feature " + n + " that the plugin does not compute");
            }
        }
        double[] mean = doubles(meta.getAsJsonArray("scaler_mean"), cols.length);
        double[] scale = doubles(meta.getAsJsonArray("scaler_scale"), cols.length);
        LgbmModel model = LgbmModel.load(dir.resolve("model.txt"));
        if (model.featureCount() != cols.length) {
            throw new IOException("model.txt has " + model.featureCount() + " features, meta has " + cols.length);
        }
        return new ModelBundle(model, meta.get("threshold").getAsDouble(), cols, mean, scale);
    }

    public static ModelBundle of(LgbmModel model, double threshold, int[] columns, double[] mean, double[] scale) {
        return new ModelBundle(model, threshold, columns, mean, scale);
    }

    // same as train/lgbm.py transform: scaled, non finite stays NaN
    public double score(double[] features) {
        double[] x = new double[columns.length];
        for (int i = 0; i < columns.length; i++) {
            double v = features[columns[i]];
            x[i] = Double.isFinite(v) ? (v - mean[i]) / scale[i] : Double.NaN;
        }
        return model.predictProba(x);
    }

    private static double[] doubles(JsonArray arr, int n) throws IOException {
        if (arr == null || arr.size() != n) {
            throw new IOException("scaler arrays must match feature_names");
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = arr.get(i).getAsDouble();
        }
        return out;
    }
}
