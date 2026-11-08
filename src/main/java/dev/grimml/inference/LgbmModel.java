package dev.grimml.inference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// lightgbm text model, numerical splits only, binary objective; same decision rule as lightgbm NumericalDecision
public final class LgbmModel {

    private static final double ZERO = 1e-35;

    private final Tree[] trees;
    private final double sigmoid;
    private final int featureCount;

    private LgbmModel(Tree[] trees, double sigmoid, int featureCount) {
        this.trees = trees;
        this.sigmoid = sigmoid;
        this.featureCount = featureCount;
    }

    public int featureCount() {
        return featureCount;
    }

    public double predictProba(double[] x) {
        double raw = 0.0;
        for (Tree t : trees) {
            raw += t.leaf(x);
        }
        return 1.0 / (1.0 + Math.exp(-sigmoid * raw));
    }

    public static LgbmModel load(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static LgbmModel parse(List<String> lines) throws IOException {
        Map<String, String> head = new HashMap<>();
        List<Tree> trees = new ArrayList<>();
        Map<String, String> cur = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("Tree=")) {
                if (cur != null) {
                    trees.add(Tree.of(cur));
                }
                cur = new HashMap<>();
                continue;
            }
            if (line.equals("end of trees")) {
                break;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            (cur == null ? head : cur).put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (cur != null) {
            trees.add(Tree.of(cur));
        }
        String objective = head.getOrDefault("objective", "");
        if (!objective.startsWith("binary")) {
            throw new IOException("only binary lightgbm models are supported, got " + objective);
        }
        if (!"1".equals(head.getOrDefault("num_class", "1"))) {
            throw new IOException("num_class must be 1");
        }
        double sig = 1.0;
        for (String part : objective.split(" ")) {
            if (part.startsWith("sigmoid:")) {
                sig = Double.parseDouble(part.substring(8));
            }
        }
        int features = Integer.parseInt(head.getOrDefault("max_feature_idx", "-1")) + 1;
        if (trees.isEmpty()) {
            throw new IOException("model has no trees");
        }
        return new LgbmModel(trees.toArray(Tree[]::new), sig, features);
    }

    private record Tree(int[] feature, double[] threshold, int[] decision, int[] left, int[] right, double[] leafValue) {

        static Tree of(Map<String, String> m) throws IOException {
            if (!"0".equals(m.getOrDefault("num_cat", "0"))) {
                throw new IOException("categorical splits are not supported");
            }
            double[] leaves = doubles(m.get("leaf_value"));
            if (Integer.parseInt(m.getOrDefault("num_leaves", "1")) <= 1) {
                return new Tree(new int[0], new double[0], new int[0], new int[0], new int[0], leaves);
            }
            return new Tree(ints(m.get("split_feature")), doubles(m.get("threshold")), ints(m.get("decision_type")),
                    ints(m.get("left_child")), ints(m.get("right_child")), leaves);
        }

        double leaf(double[] x) {
            if (feature.length == 0) {
                return leafValue.length == 0 ? 0.0 : leafValue[0];
            }
            int node = 0;
            while (node >= 0) {
                node = next(node, x);
            }
            return leafValue[~node];
        }

        private int next(int node, double[] x) {
            double v = feature[node] < x.length ? x[feature[node]] : Double.NaN;
            int dt = decision[node];
            int missing = (dt >> 2) & 3;
            boolean defaultLeft = (dt & 2) != 0;
            if (Double.isNaN(v) && missing != 2) {
                v = 0.0;
            }
            if ((missing == 1 && Math.abs(v) <= ZERO) || (missing == 2 && Double.isNaN(v))) {
                return defaultLeft ? left[node] : right[node];
            }
            return v <= threshold[node] ? left[node] : right[node];
        }

        private static int[] ints(String s) throws IOException {
            if (s == null) {
                throw new IOException("tree field missing");
            }
            String[] p = s.trim().split(" ");
            int[] out = new int[p.length];
            for (int i = 0; i < p.length; i++) {
                out[i] = Integer.parseInt(p[i]);
            }
            return out;
        }

        private static double[] doubles(String s) throws IOException {
            if (s == null) {
                throw new IOException("tree field missing");
            }
            String[] p = s.trim().split(" ");
            double[] out = new double[p.length];
            for (int i = 0; i < p.length; i++) {
                out[i] = Double.parseDouble(p[i]);
            }
            return out;
        }
    }
}
