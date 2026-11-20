package dev.grimml.detect.scan;

/** A fight is judged once per block and once when it ends, never after every window. */
public final class FightSelfCheck {

    private static final double THR = 0.7;   // window threshold
    private static final double LINE = 0.28; // alert share
    private static final int BLOCK = 60;
    private static final int MIN = 20;
    private static final long GAP = 20_000_000_000L;
    private static final long STEP = 2_000_000_000L; // one window every 2 s

    public static void main(String[] args) {
        // the old bug: 4 of the first 12 windows flagged (33%) crossed the line at once; the whole block is 22%
        Fight f = new Fight();
        long t = 0;
        Fight.Verdict v = null;
        for (int i = 0; i < BLOCK; i++) {
            v = f.add(t += STEP, (i < 12 ? i % 3 == 0 : i % 4 == 0 && i < 48) ? 0.9 : 0.1, THR, LINE, BLOCK, MIN, GAP, 0, 10);
            check(i == BLOCK - 1 || v == null, "no verdict before the block is full (window " + i + ")");
        }
        check(v != null && v.windows() == 60 && v.flagged() == 13 && !v.alert(), "early burst, 22% over the block: " + v);
        check(f.windows() == 0, "counters restart after a block");

        // a block that really is above the line flags once, with the mean score as confidence
        v = null;
        for (int i = 0; i < BLOCK; i++) {
            Fight.Verdict r = f.add(t += STEP, i % 2 == 0 ? 0.9 : 0.1, THR, LINE, BLOCK, MIN, GAP, 0, 10);
            v = r != null ? r : v;
        }
        check(v != null && v.alert() && v.flagged() == 30 && Math.abs(v.meanScore() - 0.5) < 1e-9, "50% block flags: " + v);

        // a fight that ends before the block is full is judged when it ends, not before
        for (int i = 0; i < 30; i++) {
            check(f.add(t += STEP, i < 12 ? 0.9 : 0.1, THR, LINE, BLOCK, MIN, GAP, 0, 10) == null, "no verdict mid-fight");
        }
        check(f.expire(t + GAP, LINE, MIN, GAP) == null, "not ended at exactly the gap");
        v = f.expire(t + GAP + 1, LINE, MIN, GAP);
        check(v != null && v.alert() && v.windows() == 30 && v.flagged() == 12, "ended fight judged (40%): " + v);
        check(f.expire(t + 2 * GAP, LINE, MIN, GAP) == null, "an ended fight is judged only once");

        // too short to judge: dropped without a verdict
        t += 10 * GAP;
        for (int i = 0; i < MIN - 1; i++) {
            f.add(t += STEP, 0.9, THR, LINE, BLOCK, MIN, GAP, 0, 10);
        }
        check(f.expire(t + GAP + 1, LINE, MIN, GAP) == null && f.windows() == 0, "under fight-min-windows: no verdict");

        // a new window after the gap closes the old fight (if no scan judged it) and starts a new one
        t += 10 * GAP;
        for (int i = 0; i < 25; i++) {
            f.add(t += STEP, 0.9, THR, LINE, BLOCK, MIN, GAP, 0, 10);
        }
        v = f.add(t += GAP + STEP, 0.1, THR, LINE, BLOCK, MIN, GAP, 0, 10);
        check(v != null && v.windows() == 25 && v.alert(), "old fight judged when the next one starts: " + v);
        check(f.windows() == 1, "the new window starts the next fight");

        // early alert: a blatant stretch alerts once before the block is full, and its block verdict is dropped
        t += 10 * GAP;
        Fight e = new Fight();
        Fight.Verdict early = null;
        int at = -1;
        for (int i = 0; i < BLOCK; i++) {
            Fight.Verdict r = e.add(t += STEP, i % 5 == 4 ? 0.1 : 0.9, THR, LINE, BLOCK, MIN, GAP, 3.0, 10);
            if (r != null) {
                check(early == null && i < BLOCK - 1, "early alert only once, block verdict dropped: " + r);
                early = r;
                at = i;
            }
        }
        check(early != null && early.alert() && at == 9, "80% stretch alerts early at window 10 (index " + at + "): " + early);
        check(e.windows() == 0, "block still closes after an early alert");

        // a borderline stretch (35% against a 28% line) is not sure enough to alert early; the block judges it
        Fight b = new Fight();
        Fight.Verdict bv = null;
        for (int i = 0; i < BLOCK; i++) {
            Fight.Verdict r = b.add(t += STEP, i % 20 < 7 ? 0.9 : 0.1, THR, LINE, BLOCK, MIN, GAP, 3.0, 10);
            check(r == null || i == BLOCK - 1, "no early alert for a borderline stretch (window " + i + ")");
            bv = r != null ? r : bv;
        }
        check(bv != null && bv.alert() && bv.flagged() == 21, "borderline stretch judged at the block: " + bv);
        check(Math.abs(Fight.wilsonLow(8, 10, 3.0) - 0.348) < 0.005, "wilson low bound: " + Fight.wilsonLow(8, 10, 3.0));

        // settings: the block is never shorter than the minimum
        var s = new Scanner.Settings(160, 2, 40, 20_000L, 5, 20, 3.0, 10);
        check(s.blockWindows() == 20 && s.judgeMinWindows() == 20, "block raised to the minimum: " + s);
        System.out.println("fight judging: all checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
