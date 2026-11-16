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
        schema4(fixture.resolveSibling("windows4.txt"), byCheck);
        live(fixture.resolveSibling("live_grid.txt"));
        lunge(fixture.resolveSibling("live_lunge.txt"));
        System.out.printf("parity: %d windows, %d scores match Python (worst feature diff %.1e, worst score diff %.1e)%n",
                windows, scores, worstFeat, worstScore);
    }

    /** Schema 4 features: Java (Schema4Features) vs Python (schema4.py) on real schema 4 windows. */
    private static void schema4(Path file, Map<String, ModuleModel> byCheck) throws Exception {
        if (!Files.exists(file)) {
            System.out.println("parity: no schema 4 fixture; skipped");
            return;
        }
        List<String> lines = Files.readAllLines(file);
        String[] names = lines.get(0).substring("# features=".length()).split(",");
        int windows = 0;
        int compared = 0;
        int scores4 = 0;
        int spearMace = 0;
        double worst = 0;
        for (String line : lines) {
            boolean w3 = line.startsWith("W3|");   // schema 3 spear-mace window: compute(w, false)
            if (!line.startsWith("W4|") && !w3) {
                continue;
            }
            String[] part = line.split("\\|", -1);
            String[] exp = part[1].split(",");
            String[] frames = part[2].isEmpty() ? new String[0] : part[2].split(";");
            String[] events = part[3].isEmpty() ? new String[0] : part[3].split(";");
            var w = new dev.grimml.detect.features.Window4(frames.length, events.length);
            int[] yc = new int[frames.length], pc = new int[frames.length], xs = new int[frames.length], zs = new int[frames.length];
            for (String fs : frames) {
                String[] v = fs.split(",");
                int k = w.n++;
                w.tick[k] = Integer.parseInt(v[0]);
                w.flags[k] = Integer.parseInt(v[1]);
                w.clientTick[k] = Integer.parseInt(v[2]);
                w.hasRot[k] = v[3].equals("1");
                w.yawExact[k] = (float) Double.parseDouble(v[4]);
                w.pitchExact[k] = (float) Double.parseDouble(v[5]);
                w.hasOpp[k] = v[6].equals("1");
                w.oppDx[k] = Integer.parseInt(v[7]);
                w.oppDy[k] = Integer.parseInt(v[8]);
                w.oppDz[k] = Integer.parseInt(v[9]);
                w.oppState[k] = Integer.parseInt(v[10]);
                w.oppBlockAgo[k] = Integer.parseInt(v[11]);
                w.oppHurtAgo[k] = Integer.parseInt(v[12]);
                w.maxGap[k] = Integer.parseInt(v[13]);
                w.burst[k] = Integer.parseInt(v[14]);
                yc[k] = Integer.parseInt(v[15]);
                pc[k] = Integer.parseInt(v[16]);
                xs[k] = Integer.parseInt(v[17]);
                zs[k] = Integer.parseInt(v[18]);
                w.yMilli[k] = Integer.parseInt(v[19]);
            }
            for (String es : events) {
                String[] v = es.split(",");
                int k = w.m++;
                w.eType[k] = Integer.parseInt(v[0]);
                w.eTick[k] = Integer.parseInt(v[1]);
                w.eNanos[k] = Long.parseLong(v[2]);
                w.eA[k] = Long.parseLong(v[3]);
                w.eB[k] = Integer.parseInt(v[4]);
                w.eC[k] = Integer.parseInt(v[5]);
                w.eD[k] = Integer.parseInt(v[6]);
                w.eG[k] = Integer.parseInt(v[7]);
                w.eClientTick[k] = Integer.parseInt(v[8]);
            }
            double[] got = dev.grimml.detect.features.Schema4Features.compute(w, !w3);
            // model scores on the combined vector (13 core + 44 schema 4), as the Scanner builds it
            java.util.List<Long> att = new java.util.ArrayList<>();
            for (int e = 0; e < w.m; e++) if (w.eType[e] == dev.grimml.detect.features.Window4.ATTACK) att.add(w.eNanos[e]);
            long[] attacks = att.stream().mapToLong(Long::longValue).toArray();
            double[] core = Features.compute(yc, pc, xs, zs, w.flags, w.n, attacks, attacks.length);
            double[] combined = new double[core.length + got.length];
            System.arraycopy(core, 0, combined, 0, core.length);
            System.arraycopy(got, 0, combined, core.length, got.length);
            if (part.length > 4 && !part[4].isEmpty()) {
                for (String kv : part[4].split(";")) {
                    String[] p = kv.split("=");
                    double d = Math.abs(Double.parseDouble(p[1]) - byCheck.get(p[0]).score(combined));
                    if (d > 1e-9) {
                        throw new AssertionError("schema4 window " + windows + " " + p[0] + " score: python " + p[1]
                                + " java " + byCheck.get(p[0]).score(combined));
                    }
                    scores4++;
                }
            }
            for (int i = 0; i < names.length; i++) {
                double e = exp[i].equals("nan") ? Double.NaN : Double.parseDouble(exp[i]);
                double g = got[dev.grimml.detect.features.Schema4Features.indexOf(names[i])];
                if (Double.isNaN(e) != Double.isNaN(g)) {
                    throw new AssertionError("schema4 window " + windows + " " + names[i] + ": python " + e + " java " + g);
                }
                if (!Double.isNaN(e)) {
                    double d = Math.abs(e - g) / Math.max(1.0, Math.abs(e));
                    worst = Math.max(worst, d);
                    if (d > 1e-9) {
                        throw new AssertionError("schema4 window " + windows + " " + names[i] + ": python " + e + " java " + g);
                    }
                    compared++;
                    if (i >= names.length - dev.grimml.detect.features.SpearMace.NAMES.length) spearMace++;
                }
            }
            windows++;
        }
        System.out.printf("parity: schema 4 features match Python on %d windows (%d values, worst diff %.1e), %d model scores match, "
                + "%d spear-mace values%n", windows, compared, worst, scores4, spearMace);
    }

    /** Live AimGrid check: the same frame streams must alert on the same ticks and learn the same step. */
    private static void live(Path file) throws Exception {
        if (!Files.exists(file)) {
            System.out.println("parity: no live AimGrid fixture; skipped");
            return;
        }
        List<String> lines = Files.readAllLines(file);
        int streams = 0, alerts = 0, frames = 0;
        for (int li = 0; li < lines.size(); li++) {
            if (!lines.get(li).startsWith("F|")) {
                continue;
            }
            String[] fs = lines.get(li).substring(2).split(";");
            String exp = lines.get(li + 1).substring(2);
            double expStep = lines.get(li + 2).substring(2).equals("nan") ? Double.NaN : Double.parseDouble(lines.get(li + 2).substring(2));
            var g = new dev.grimml.detect.features.AimGrid.Live(0.01, 0.10, 20.0, 0.05);
            StringBuilder got = new StringBuilder();
            for (String f : fs) {
                String[] v = f.split(",");
                int tick = Integer.parseInt(v[0]);
                if (g.feed(tick, v[1].equals("1"), (float) Double.parseDouble(v[2]), (float) Double.parseDouble(v[3]),
                        v[4].equals("1"), v[5].equals("1"), Double.parseDouble(v[6]))) {
                    got.append(got.length() == 0 ? "" : ",").append(tick);
                    alerts++;
                }
                frames++;
            }
            if (!got.toString().equals(exp)) {
                throw new AssertionError("live AimGrid stream " + streams + " alerts differ: python [" + exp + "] java [" + got + "]");
            }
            if (Double.isNaN(expStep) != Double.isNaN(g.step()) || (!Double.isNaN(expStep) && Math.abs(expStep - g.step()) > 1e-12)) {
                throw new AssertionError("live AimGrid stream " + streams + " step: python " + expStep + " java " + g.step());
            }
            streams++;
        }
        System.out.printf("parity: live AimGrid matches Python on %d streams (%d frames, %d alerts)%n", streams, frames, alerts);
    }

    /** Live LungeRate check: the same swap / STAB streams must alert on the same events. */
    private static void lunge(Path file) throws Exception {
        if (!Files.exists(file)) {
            System.out.println("parity: no live LungeRate fixture; skipped");
            return;
        }
        List<String> lines = Files.readAllLines(file);
        int streams = 0, alerts = 0, events = 0;
        for (int li = 0; li < lines.size(); li++) {
            if (!lines.get(li).startsWith("E|")) {
                continue;
            }
            String exp = lines.get(li + 1).substring(2);
            int expLunges = Integer.parseInt(lines.get(li + 2).substring(2));
            var r = new dev.grimml.detect.features.LungeRate(300.0, 75.0, 6, 30000.0, 150.0);
            StringBuilder got = new StringBuilder();
            for (String e : lines.get(li).substring(2).split(";")) {
                String[] v = e.split(",");
                long n = Long.parseLong(v[1]);
                if (v[0].equals("1")) {
                    r.swap(n, Integer.parseInt(v[2]));
                } else if (r.stab(n)) {
                    got.append(got.length() == 0 ? "" : ",").append(n);
                    alerts++;
                }
                events++;
            }
            if (!got.toString().equals(exp) || r.lunges() != expLunges) {
                throw new AssertionError("live LungeRate stream " + streams + " differs: python [" + exp + "] " + expLunges
                        + " java [" + got + "] " + r.lunges());
            }
            streams++;
        }
        System.out.printf("parity: live LungeRate matches Python on %d streams (%d events, %d alerts)%n", streams, events, alerts);
    }

    private static int[] ints(String s) {
        return java.util.Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray();
    }
}
