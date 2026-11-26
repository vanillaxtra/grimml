package dev.grimml.detect.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Fight sampling: fights, bands, the low rate, the random slice, the per-player cap, holding, labels and trusted players. */
public final class FightSamplerSelfCheck {

    private static final long MIN = 60_000L;

    public static void main(String[] args) {
        // a fight per call: n windows 2 s apart, the first flaggedWindows of them past the threshold
        List<String> out = new ArrayList<>();
        FightSampler s = new FightSampler(new FightSampler.Sampling(0.25, 1.0, 0.0, 6, 0, 10_000L),
                out::addAll, new Random(1), 100);
        long t = 1_000_000L;

        // bands
        t = fight(s, "a", t, 4, 0, false);
        t = fight(s, "b", t, 4, 2, false);
        t = fight(s, "c", t, 4, 4, true);
        s.tick(t + MIN);
        List<String> fights = lines(out, "fight");
        check(fights.stream().anyMatch(l -> l.contains("\"p\":\"b\"") && l.contains("\"band\":\"unsure\"")
                && l.contains("\"flagged\":2") && l.contains("\"keep\":1,")), "unsure fight: " + fights);
        check(fights.stream().anyMatch(l -> l.contains("\"p\":\"c\"") && l.contains("\"band\":\"high\"")
                && l.contains("\"max\":{\"MLKillAura\":0.9}")), "high fight: " + fights);
        check(lines(out, "window").size() >= 8 && lines(out, "window").stream().allMatch(l -> l.contains("\"g\":")),
                "window lines with g");
        String g = fights.get(0).replaceAll(".*\"g\":(\\d+).*", "$1");
        check(out.get(out.indexOf(fights.get(0)) + 1).contains("\"g\":" + g), "windows follow their fight line");

        // a rule check can alert before the first scored window: the fight that starts right after is high
        out.clear();
        s.alert("early", t);
        t = fight(s, "early", t + 3_000L, 3, 0, false);
        s.alert("stale", t);
        t = fight(s, "stale", t + 60_000L, 3, 0, false);
        s.tick(t + MIN);
        check(lines(out, "fight").stream().anyMatch(l -> l.contains("\"p\":\"early\"") && l.contains("\"band\":\"high\""))
                && lines(out, "fight").stream().noneMatch(l -> l.contains("\"p\":\"stale\"") && l.contains("\"band\":\"high\"")),
                "alert just before a fight: " + lines(out, "fight"));

        // the low band is sent at about balance x (high + unsure), and says how likely it was
        out.clear();
        for (int i = 0; i < 2000; i++) { // 1 high fight in 100, mixed in like on a real server
            t = i % 100 == 0 ? fight(s, "h" + i, t, 4, 4, true) : fight(s, "l" + i, t, 4, 0, false);
        }
        s.tick(t + MIN);
        long low = lines(out, "fight").stream().filter(l -> l.contains("\"band\":\"low\"")).count();
        check(low >= 8 && low <= 60, "low fights sent: " + low + " of 2000");
        // at the start (1 high, 1 low seen) the rate is 1; once it settles it is about 1 in 100
        check(lines(out, "fight").stream().filter(l -> l.contains("\"band\":\"low\"")).anyMatch(l -> l.contains("\"keep\":0.0")),
                "low keep settles near 0.01");

        // the random slice: about randomShare of low fights, tagged rand
        out.clear();
        FightSampler r = new FightSampler(new FightSampler.Sampling(0.25, 0.0, 0.5, 6, 0, 10_000L), out::addAll,
                new Random(2), 1);
        for (int i = 0; i < 400; i++) {
            t = fight(r, "r" + i, t, 3, 0, false);
        }
        r.tick(t + MIN);
        List<String> rf = lines(out, "fight");
        check(rf.size() > 150 && rf.size() < 250 && rf.stream().allMatch(l -> l.contains("\"rand\":true")
                && l.contains("\"keep\":0.5,")), "random slice: " + rf.size() + " of 400");

        // one player: at most perPlayerHour kept fights an hour
        out.clear();
        for (int i = 0; i < 10; i++) {
            t = fight(s, "spam", t, 3, 3, true);
        }
        s.tick(t + MIN);
        check(lines(out, "fight").size() == 6, "per-player cap: " + lines(out, "fight").size());
        check(s.summary().contains("over the per-player cap"), "cap in summary: " + s.summary());

        // holding: a fight waits holdMinutes, so a late alert and a staff label still reach it
        out.clear();
        FightSampler h = new FightSampler(new FightSampler.Sampling(0.25, 0.0, 0.0, 6, 5, 10_000L), out::addAll,
                new Random(3), 1);
        long t0 = t;
        t = fight(h, "late", t, 3, 0, false);
        t = fight(h, "lab", t, 3, 0, false);
        t = fight(h, "banned", t, 3, 0, false);
        h.tick(t + MIN);
        check(out.isEmpty(), "nothing decided before hold-minutes");
        h.alert("late", t + MIN);
        check(h.label("lab", "cheat", "killaura", t + MIN) == 1, "label reaches the waiting fight");
        check(h.ban("banned") == 1, "ban reaches the waiting fight");
        h.tick(t0 + 12 * MIN);
        List<String> hf = lines(out, "fight");
        check(hf.size() == 3 && hf.stream().anyMatch(l -> l.contains("\"p\":\"late\"") && l.contains("\"band\":\"high\"")),
                "late alert makes it high: " + hf);
        check(hf.stream().anyMatch(l -> l.contains("\"p\":\"lab\"") && l.contains("\"label\":\"cheat\",\"by\":\"staff\","
                + "\"kind\":\"killaura\"") && l.contains("\"keep\":1,")), "labelled fight kept: " + hf);
        check(hf.stream().anyMatch(l -> l.contains("\"p\":\"banned\"") && l.contains("\"banned\":true")
                && !l.contains("\"label\"")), "banned player's fight kept, not labelled: " + hf);

        // the label also covers the player's next fights, and labelled fights skip the per-player cap
        out.clear();
        t = t0 + 13 * MIN;
        for (int i = 0; i < 8; i++) {
            t = fight(h, "lab", t, 2, 0, false);
        }
        h.tick(t + MIN);
        h.tick(t + 10 * MIN);
        check(lines(out, "fight").size() == 8 && lines(out, "fight").stream().allMatch(l -> l.contains("\"label\":\"cheat\"")),
                "next fights labelled, no cap: " + lines(out, "fight").size());

        // trusted players are sent labelled legit
        out.clear();
        t = fight(h, "staff", t, 2, 0, false, true);
        h.tick(t + MIN);
        h.tick(t + 10 * MIN);
        check(lines(out, "fight").size() == 1 && lines(out, "fight").get(0).contains("\"label\":\"legit\",\"by\":\"trusted\""),
                "trusted: " + lines(out, "fight"));

        // a long fight is cut into pieces
        out.clear();
        t = fight(h, "long", t, FightSampler.MAX_FIGHT_WINDOWS + 10, 0, false, true);
        h.tick(t + MIN);
        h.tick(t + 10 * MIN);
        check(lines(out, "fight").size() == 2, "long fight split: " + lines(out, "fight").size());
        System.out.println("fight sampler: all checks passed");
    }

    private static long fight(FightSampler s, String p, long t, int n, int flagged, boolean alert) {
        return fight(s, p, t, n, flagged, alert, false);
    }

    private static long fight(FightSampler s, String p, long t, int n, int flagged, boolean alert, boolean trusted) {
        for (int i = 0; i < n; i++) {
            boolean f = i < flagged;
            long at = t;
            s.window(p, t, trusted, f, Map.of("MLKillAura", f ? 0.9 : 0.1),
                    g -> "{\"type\":\"window\",\"g\":" + g + ",\"p\":\"" + p + "\",\"t\":" + at / 1000 + "}");
            t += 2_000L;
        }
        if (alert) {
            s.alert(p, t);
        }
        s.tick(t);
        return t + 60_000L; // next fight after the gap
    }

    private static List<String> lines(List<String> out, String type) {
        return out.stream().filter(l -> l.startsWith("{\"type\":\"" + type + "\"")).toList();
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
