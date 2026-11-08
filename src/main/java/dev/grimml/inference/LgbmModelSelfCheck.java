package dev.grimml.inference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// scores training/tests/fixtures/lgbm_parity/cases.json with the plugin tree walker and compares to lightgbm
public final class LgbmModelSelfCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "training/tests/fixtures/lgbm_parity");
        ModelBundle bundle = ModelBundle.load(dir);
        var cases = JsonParser.parseString(Files.readString(dir.resolve("cases.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        double worst = 0.0;
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            JsonObject f = c.getAsJsonObject("features");
            double[] x = new double[FeatureExtractor.NAMES.length];
            Arrays.fill(x, Double.NaN);
            for (int i = 0; i < x.length; i++) {
                JsonElement v = f.get(FeatureExtractor.NAMES[i]);
                if (v != null && !v.isJsonNull()) {
                    x[i] = v.getAsDouble();
                }
            }
            double got = bundle.score(x);
            double want = c.get("proba").getAsDouble();
            worst = Math.max(worst, Math.abs(got - want));
        }
        if (worst > 1e-9) {
            throw new AssertionError("lightgbm parity off by " + worst);
        }
        System.out.println("lgbm parity self-check passed: " + cases.size() + " cases max diff " + worst);
    }
}
