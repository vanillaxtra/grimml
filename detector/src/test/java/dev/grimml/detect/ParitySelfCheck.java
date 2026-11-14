package dev.grimml.detect;

import dev.grimml.detect.features.Features;
import dev.grimml.detect.model.ModelSet;
import dev.grimml.detect.model.ModuleModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java must give the same features and scores as Python on real recorded windows
 * (fixture written by training/export_parity.py), using the published models in ../models.
 * args: [fixture dir] [models dir]
 */
public final class ParitySelfCheck {

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args.length > 0 ? args[0] : "src/test/resources/parity").resolve("windows.txt");
        Path models = Path.of(args.length > 1 ? args[1] : "../models");
        if (!Files.exists(fixture)) {
            System.out.println("parity: no fixture, run training/export_parity.py; skipped");
            return;
        }
        ModelSet set = ModelSet.build(ModelSet.parseManifest(Files.readAllBytes(models.resolve("manifest.json"))),
                name -> Files.readAllBytes(models.resolve(name)));
        Map<String, ModuleModel> byCheck = new HashMap<>();
        for (ModuleModel m : set.models()) {
            byCheck.put(m.checkName(), m);
        }

        List<String> lines = Files.readAllLines(fixture);
        String[] names = lines.get(0).substring("# features=".length()).split(",");
        int windows = 0;
        int scores = 0;
        double worstFeat = 0;
        double worstScore = 0;
        for (String line : lines) {
            if (!line.startsWith("W|")) {
                continue;
            }
            String[] part = line.split("\\|", -1);
            String[] expF = part[1].split(",");
            String[] cols = part[3].split(";");
            int[] yaw = ints(cols[0]);
            int[] pitch = ints(cols[1]);
            int[] x = ints(cols[2]);
            int[] z = ints(cols[3]);
            int[] flags = ints(cols[4]);
            long[] att = part[4].isEmpty() ? new long[0] : java.util.Arrays.stream(part[4].split(",")).mapToLong(Long::parseLong).toArray();

            double[] got = Features.compute(yaw, pitch, x, z, flags, yaw.length, att, att.length);
            for (int i = 0; i < names.length; i++) {
                double e = expF[i].equals("nan") ? Double.NaN : Double.parseDouble(expF[i]);
                double g = got[Features.indexOf(names[i])];
                if (Double.isNaN(e) != Double.isNaN(g)) {
                    throw new AssertionError("window " + windows + " " + names[i] + ": python " + e + " java " + g);
                }
                if (!Double.isNaN(e)) {
                    double d = Math.abs(e - g) / Math.max(1.0, Math.abs(e));
                    worstFeat = Math.max(worstFeat, d);
                    if (d > 1e-9) {
                        throw new AssertionError("window " + windows + " " + names[i] + ": python " + e + " java " + g);
                    }
                }
            }
            for (String kv : part[2].split(";")) {
                String[] p = kv.split("=");
                ModuleModel m = byCheck.get(p[0]);
                if (m == null) {
                    throw new AssertionError("fixture has a score for " + p[0] + " but models/ does not");
                }
                double d = Math.abs(Double.parseDouble(p[1]) - m.score(got));
                worstScore = Math.max(worstScore, d);
                if (d > 1e-9) {
                    throw new AssertionError("window " + windows + " " + p[0] + ": python " + p[1] + " java " + m.score(got));
                }
                scores++;
            }
            windows++;
        }
        if (windows == 0) {
            throw new AssertionError("fixture has no windows");
        }
        // the plugin's startup path: models bundled in the jar load and match the published set
        var store = new dev.grimml.detect.model.ModelStore(Files.createTempDirectory("gmld"), java.util.logging.Logger.getLogger("parity"));
        store.loadLocal();
        if (store.current().version() != set.version() || store.current().models().size() != set.models().size()) {
            throw new AssertionError("bundled models did not load: " + store.lastError());
        }
        System.out.printf("parity: %d windows, %d scores match Python (worst feature diff %.1e, worst score diff %.1e)%n",
                windows, scores, worstFeat, worstScore);
    }

    private static int[] ints(String s) {
        return java.util.Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray();
    }
}
