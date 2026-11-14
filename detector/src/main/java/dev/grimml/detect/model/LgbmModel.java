package dev.grimml.detect.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a LightGBM text model (Booster.save_model) and scores it in plain Java, no native library.
 * Supports what the trainer produces: binary objective, numerical splits, NaN/zero/none missing handling.
 * The decision rule copies LightGBM's Tree::NumericalDecision so scores match the Python booster.
 */
public final class LgbmModel {

    private static final double ZERO_THRESHOLD = 1e-35;
    private static final int MISSING_NONE = 0;
    private static final int MISSING_ZERO = 1;
    private static final int MISSING_NAN = 2;

    private final Tree[] trees;
    private final int numFeatures;
    private final double sigmoid;

    private LgbmModel(Tree[] trees, int numFeatures, double sigmoid) {
        this.trees = trees;
        this.numFeatures = numFeatures;
        this.sigmoid = sigmoid;
    }

    public int numFeatures() {
        return numFeatures;
    }

    public int numTrees() {
        return trees.length;
    }

    /** Raw score (sum of leaf values), before the sigmoid. */
    public double raw(double[] x) {
        if (x.length < numFeatures) {
            throw new IllegalArgumentException("expected " + numFeatures + " features, got " + x.length);
        }
        double s = 0.0;
        for (Tree t : trees) {
            s += t.predict(x);
        }
        return s;
    }

    /** Probability of the positive (cheat) class, same as predict_proba[:, 1]. */
    public double predict(double[] x) {
        return 1.0 / (1.0 + Math.exp(-sigmoid * raw(x)));
    }

    public static LgbmModel parse(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        Map<String, String> header = new HashMap<>();
        List<Tree> trees = new ArrayList<>();
        Map<String, String> cur = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
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
            (cur == null ? header : cur).put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (cur != null) {
            trees.add(Tree.of(cur));
        }
        if (trees.isEmpty()) {
            throw new IOException("no trees in model");
        }
        String objective = header.getOrDefault("objective", "");
        if (!objective.startsWith("binary")) {
            throw new IOException("only binary models are supported, got objective=" + objective);
        }
        if (!"1".equals(header.getOrDefault("num_class", "1")) || !"1".equals(header.getOrDefault("num_tree_per_iteration", "1"))) {
            throw new IOException("only single-class models are supported");
        }
        double sig = 1.0;
        for (String part : objective.split(" ")) {
            if (part.startsWith("sigmoid:")) {
                sig = Double.parseDouble(part.substring("sigmoid:".length()));
            }
        }
        int maxIdx = Integer.parseInt(header.getOrDefault("max_feature_idx", "-1"));
        return new LgbmModel(trees.toArray(new Tree[0]), maxIdx + 1, sig);
    }

    private static final class Tree {
        final int[] feature;
        final double[] threshold;
        final int[] decision;
        final int[] left;
        final int[] right;
        final double[] leaf;

        private Tree(int[] feature, double[] threshold, int[] decision, int[] left, int[] right, double[] leaf) {
            this.feature = feature;
            this.threshold = threshold;
            this.decision = decision;
            this.left = left;
            this.right = right;
            this.leaf = leaf;
        }

        static Tree of(Map<String, String> m) throws IOException {
            int leaves = Integer.parseInt(m.getOrDefault("num_leaves", "1"));
            double[] leaf = doubles(m.get("leaf_value"));
            if (leaf.length != leaves) {
                throw new IOException("leaf_value has " + leaf.length + " values, num_leaves=" + leaves);
            }
            if (leaves == 1) {
                return new Tree(new int[0], new double[0], new int[0], new int[0], new int[0], leaf);
            }
            if (!"0".equals(m.getOrDefault("num_cat", "0"))) {
                throw new IOException("categorical splits are not supported");
            }
            int[] f = ints(m.get("split_feature"));
            double[] t = doubles(m.get("threshold"));
            int[] d = ints(m.get("decision_type"));
            int[] l = ints(m.get("left_child"));
            int[] rr = ints(m.get("right_child"));
            int n = leaves - 1;
            if (f.length != n || t.length != n || d.length != n || l.length != n || rr.length != n) {
                throw new IOException("tree arrays do not match num_leaves=" + leaves);
            }
            return new Tree(f, t, d, l, rr, leaf);
        }

        double predict(double[] x) {
            if (feature.length == 0) {
                return leaf[0];
            }
            int node = 0;
            while (node >= 0) {
                node = decide(x[feature[node]], node) ? left[node] : right[node];
            }
            return leaf[~node];
        }

        // LightGBM Tree::NumericalDecision
        private boolean decide(double v, int node) {
            int dt = decision[node];
            int missing = (dt >> 2) & 3;
            boolean defaultLeft = (dt & 2) != 0;
            if (Double.isNaN(v) && missing != MISSING_NAN) {
                v = 0.0;
            }
            if ((missing == MISSING_ZERO && Math.abs(v) <= ZERO_THRESHOLD) || (missing == MISSING_NAN && Double.isNaN(v))) {
                return defaultLeft;
            }
            return v <= threshold[node];
        }
    }

    private static int[] ints(String s) throws IOException {
        if (s == null) {
            throw new IOException("missing tree field");
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
            throw new IOException("missing tree field");
        }
        String[] p = s.trim().split(" ");
        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = Double.parseDouble(p[i]);
        }
        return out;
    }
}
