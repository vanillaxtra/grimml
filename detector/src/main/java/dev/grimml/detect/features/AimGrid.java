package dev.grimml.detect.features;

/**
 * Java port of training/grimml_train/features/aimgrid.py (mouse-grid aim check). Same constants, same order of
 * float operations: ParitySelfCheck compares the window features and the live alerts with Python. Change both.
 *
 * Real mouse rotation is a whole number of sensitivity steps, step = (sens*0.6+0.2)^3 * 8 * 0.15, on yaw and pitch.
 * Aim assist, aimbots and kill aura add rotation that is not.
 */
public final class AimGrid {

    public static final double TOL = 0.10;
    public static final double MAX_DELTA = 30.0;
    public static final double MIN_DELTA = 1e-4;
    static final int MIN_SAMPLES = 30;
    static final double MIN_FIT = 0.5;
    static final int COARSE_N = 4001;
    static final int SMALLEST = 96;
    static final double COARSE_MAX = 2.0;
    static final double NEAR_BEST = 0.9;
    private static final double[] COARSE_STEPS = new double[COARSE_N];
    private static final double[][] REFINE = {{20.0, 0.25}, {20.0, TOL / 2}, {100.0, TOL / 2}, {400.0, TOL / 2}, {1e9, TOL / 2}};

    public static final String[] NAMES = {"grid_off_frac", "grid_off_toward", "grid_off_away", "grid_toward_minus_away"};

    static {
        for (int i = 0; i < COARSE_N; i++) {
            COARSE_STEPS[i] = stepFor((double) i / (COARSE_N - 1));
        }
    }

    private AimGrid() {
    }

    public static double stepFor(double sens) {
        double f = sens * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    private static boolean ratioOff(double d, double step) {
        double r = d / step;
        double n = Math.floor(r + 0.5);
        return n < 1 || Math.abs(r - n) > TOL;
    }

    public static boolean offGrid(double d, double step) {
        return ratioOff(Math.abs(d), step);
    }

    private static double onShare(double[] ds, int from, int to, double step) {
        int on = 0;
        for (int i = from; i < to; i++) {
            if (!ratioOff(ds[i], step)) {
                on++;
            }
        }
        return (double) on / (to - from);
    }

    /** Learned step, or NaN. ds[from..to): absolute deltas in time order (filtered here like Python). */
    public static double learnStep(double[] in, int from, int to) {
        double[] ds = new double[to - from];
        int n = 0;
        for (int i = from; i < to; i++) {
            if (in[i] > MIN_DELTA && in[i] < MAX_DELTA) {
                ds[n++] = in[i];
            }
        }
        if (n < MIN_SAMPLES) {
            return Double.NaN;
        }
        double[] small = new double[SMALLEST];
        int ns = 0;
        for (int i = 0; i < n && ns < SMALLEST; i++) {
            if (ds[i] < COARSE_MAX) {
                small[ns++] = ds[i];
            }
        }
        if (ns < MIN_SAMPLES / 2) {
            return Double.NaN;
        }
        int[] on = new int[COARSE_N];
        int best = 0;
        for (int c = 0; c < COARSE_N; c++) {
            double s = COARSE_STEPS[c];
            int k = 0;
            for (int i = 0; i < ns; i++) {
                double r = small[i] / s;
                double q = Math.floor(r + 0.5);
                if (q >= 1 && Math.abs(r - q) <= TOL) {
                    k++;
                }
            }
            on[c] = k;
            best = Math.max(best, k);
        }
        if (best <= 0) {
            return Double.NaN;
        }
        double step = Double.NaN;
        for (int c = 0; c < COARSE_N; c++) {
            if (on[c] >= NEAR_BEST * best) {
                step = COARSE_STEPS[c];
            }
        }
        for (double[] lt : REFINE) {
            double num = 0, den = 0;
            for (int i = 0; i < n; i++) {
                double d = ds[i];
                double r = d / step;
                double q = Math.floor(r + 0.5);
                if (q >= 1 && q <= lt[0] && Math.abs(r - q) <= lt[1]) {
                    num += d * q;
                    den += q * q;
                }
            }
            if (den > 0) {
                step = num / den;
            }
        }
        return onShare(ds, 0, n, step) < MIN_FIT ? Double.NaN : step;
    }

    /**
     * Window features (NAMES order). hasAim[i]/errYaw[i]: the frame's aim error to the opponent (Schema4Features
     * aim track). Toward = the yaw delta shrinks the error.
     */
    public static double[] features(Window4 w, boolean[] hasAim, double[] errYaw) {
        double[] row = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double[] all = new double[2 * w.n];
        int na = 0;
        double[] yd = new double[w.n];
        int[] yi = new int[w.n];
        int ny = 0;
        for (int i = 1; i < w.n; i++) {
            if (!w.hasRot[i - 1] || !w.hasRot[i] || w.tick[i] - w.tick[i - 1] != 1) {
                continue;
            }
            double dy = (double) w.yawExact[i] - (double) w.yawExact[i - 1];
            if (Math.abs(dy) > MIN_DELTA && Math.abs(dy) < MAX_DELTA) {
                all[na++] = Math.abs(dy);
                yd[ny] = dy;
                yi[ny++] = i;
            }
            if (Math.abs((double) w.pitchExact[i]) < 89.0 && Math.abs((double) w.pitchExact[i - 1]) < 89.0) {
                double dp = Math.abs((double) w.pitchExact[i] - (double) w.pitchExact[i - 1]);
                if (dp > MIN_DELTA && dp < MAX_DELTA) {
                    all[na++] = dp;
                }
            }
        }
        double step = learnStep(all, 0, na);
        if (Double.isNaN(step)) {
            return row;
        }
        row[0] = 1.0 - onShare(all, 0, na, step);
        int tN = 0, tOff = 0, aN = 0, aOff = 0;
        for (int k = 0; k < ny; k++) {
            int i = yi[k];
            if (!hasAim[i - 1]) {
                continue;
            }
            boolean toward = (yd[k] < 0) == (errYaw[i - 1] > 0);
            boolean off = offGrid(yd[k], step);
            if (toward) {
                tN++;
                if (off) tOff++;
            } else {
                aN++;
                if (off) aOff++;
            }
        }
        if (tN >= 10) row[1] = (double) tOff / tN;
        if (aN >= 10) row[2] = (double) aOff / aN;
        if (tN >= 10 && aN >= 10) row[3] = row[1] - row[2];
        return row;
    }

    /** One-sided CUSUM over off-grid indicators. */
    static final class Cusum {
        double up, down;
        final double h;
        double s;

        Cusum(double p0, double p1, double h) {
            this.up = Math.log(p1 / p0);
            this.down = Math.log((1.0 - p1) / (1.0 - p0));
            this.h = h;
        }

        void add(boolean off) {
            s = Math.max(0.0, s + (off ? up : down));
        }
    }

    /**
     * The live AimGrid check for one player (Python LiveGrid). Not thread safe: one scan thread feeds it.
     *
     * Per-player baseline: mouse acceleration (Windows "Enhance pointer precision", Raw Accel, custom curves) puts some
     * legit rotation off the grid. The assist only acts with a target, so the off-grid share of the last BASE_KEEP
     * out-of-fight samples is the player's own rate: the test's legit rate becomes max(p0, rate * baseMargin) (rounded
     * to 0.001) and its cheat rate max(p1, 2 * that). Players over baseMax are skipped; until baseMin out-of-fight
     * samples exist fight rotation is not tested. Moves over maxMult steps are ignored (baseline and test): a tiny step
     * error grows with the size of the move and acceleration curves bend fast flicks the most.
     */
    public static final class Live {
        static final int LEARN = 200, CHECK = 200, KEEP = 600, RING = 300, MIN_SIDE = 10, BASE_KEEP = 600;
        static final double STEP_CHANGE = 0.005;

        private final Cusum cusum;
        private final double minBias, p0, p1, baseMargin, baseMax, maxMult;
        private final int baseMin;
        private final boolean[] base = new boolean[BASE_KEEP];
        private int baseStart, baseN, baseOff;
        private double legitRate;
        private final double[] buf = new double[KEEP];
        private int bufN;
        private double step = Double.NaN;
        private int since;
        private final boolean[] ringToward = new boolean[RING], ringOff = new boolean[RING];
        private int ringStart, ringN;
        private boolean hasPrev;
        private int prevTick;
        private boolean prevRot, prevAim;
        private double prevYaw, prevPitch, prevErr;
        public long samples;
        public int lastTick = Integer.MIN_VALUE;

        public Live(double p0, double p1, double h, double minBias, double baseMargin, double baseMax, int baseMin,
                    double maxMult) {
            this.cusum = new Cusum(p0, p1, h);
            this.minBias = minBias;
            this.p0 = p0;
            this.p1 = p1;
            this.baseMargin = baseMargin;
            this.baseMax = baseMax;
            this.baseMin = Math.max(1, baseMin);
            this.maxMult = maxMult;
            this.legitRate = p0;
        }

        /** Defaults as in config.yml (and Python LiveGrid()). */
        public Live(double p0, double p1, double h, double minBias) {
            this(p0, p1, h, minBias, 1.5, 0.08, 100, 20.0);
        }

        /** Off-grid share of the player's out-of-fight rotation, NaN before baseMin samples. */
        public double baseline() {
            return baseN >= baseMin ? (double) baseOff / baseN : Double.NaN;
        }

        /** The test's current legit off-grid rate (p0 raised to the player's baseline). */
        public double legitRate() {
            return legitRate;
        }

        private void baseAdd(boolean off) {
            if (baseN == BASE_KEEP) {
                if (base[baseStart]) baseOff--;
                base[baseStart] = off;
                baseStart = (baseStart + 1) % BASE_KEEP;
            } else {
                base[(baseStart + baseN) % BASE_KEEP] = off;
                baseN++;
            }
            if (off) baseOff++;
            double b = baseline();
            if (Double.isNaN(b) || b > baseMax) {
                return; // untested player (see sample): keep the last rates
            }
            double q = Math.min(0.49, Math.max(p0, Math.floor(b * baseMargin * 1000.0 + 0.5) / 1000.0));
            if (q != legitRate) {
                legitRate = q;
                double c = Math.max(p1, 2.0 * q);
                cusum.up = Math.log(c / q);
                cusum.down = Math.log((1.0 - c) / (1.0 - q));
            }
        }

        public double step() {
            return step;
        }

        public double score() {
            return cusum.s;
        }

        private void restart() {
            cusum.s = 0.0;
            ringStart = 0;
            ringN = 0;
            baseStart = 0;
            baseN = 0;
            baseOff = 0;
        }

        private void push(double d) {
            if (bufN == KEEP) {
                System.arraycopy(buf, 1, buf, 0, KEEP - 1);
                bufN--;
            }
            buf[bufN++] = d;
        }

        private void sample(double d, int toward, boolean fight) {   // toward: -1 unknown, 0 away, 1 toward
            push(d);
            samples++;
            since++;
            if (Double.isNaN(step)) {
                if (bufN >= LEARN && since >= CHECK) {
                    since = 0;
                    step = learnStep(buf, 0, bufN);
                    restart();
                }
                return;
            }
            if (since >= CHECK) {
                since = 0;
                double s2 = learnStep(buf, bufN - CHECK, bufN);
                if (Double.isNaN(s2)) {
                    step = Double.NaN;
                    restart();
                    return;
                }
                if (Math.abs(s2 - step) / step > STEP_CHANGE) {
                    step = s2;
                    restart();
                    return;
                }
            }
            if (d > maxMult * step) {
                return; // big flicks: a tiny step error and mouse acceleration both scale with the size of the move
            }
            boolean off = offGrid(d, step);
            if (!fight) {
                baseAdd(off);
                return;
            }
            double b = baseline();
            if (Double.isNaN(b) || b > baseMax) {
                cusum.s = 0.0; // no baseline yet, or a mouse too noisy to test
                return;
            }
            cusum.add(off);
            if (toward >= 0) {
                int idx;
                if (ringN == RING) {
                    idx = ringStart;
                    ringStart = (ringStart + 1) % RING;
                } else {
                    idx = (ringStart + ringN) % RING;
                    ringN++;
                }
                ringToward[idx] = toward == 1;
                ringOff[idx] = off;
            }
        }

        /** Toward minus away off-grid share over the ring, NaN with fewer than MIN_SIDE samples on a side. */
        public double bias() {
            int t = 0, tOff = 0, a = 0, aOff = 0;
            for (int k = 0; k < ringN; k++) {
                int i = (ringStart + k) % RING;
                if (ringToward[i]) {
                    t++;
                    if (ringOff[i]) tOff++;
                } else {
                    a++;
                    if (ringOff[i]) aOff++;
                }
            }
            if (t < MIN_SIDE || a < MIN_SIDE) {
                return Double.NaN;
            }
            return (double) tOff / t - (double) aOff / a;
        }

        public double offShare() {
            if (ringN == 0) return Double.NaN;
            int off = 0;
            for (int k = 0; k < ringN; k++) {
                if (ringOff[(ringStart + k) % RING]) off++;
            }
            return (double) off / ringN;
        }

        /** One frame; err is the aim error when hasAim. Returns true when this frame raises an alert. */
        public boolean feed(int tick, boolean hasRot, float yaw, float pitch, boolean hasOpp, boolean hasAim, double err) {
            boolean ok = hasPrev && hasRot && prevRot && tick - prevTick == 1;
            boolean pAim = prevAim;
            double pErr = prevErr, pYaw = prevYaw, pPitch = prevPitch;
            hasPrev = true;
            prevTick = tick;
            prevRot = hasRot;
            prevYaw = yaw;
            prevPitch = pitch;
            prevAim = hasAim;
            prevErr = err;
            lastTick = tick;
            if (!ok) {
                return false;
            }
            boolean fight = hasOpp;
            double dy = (double) yaw - pYaw;
            if (Math.abs(dy) > MIN_DELTA && Math.abs(dy) < MAX_DELTA) {
                sample(Math.abs(dy), pAim ? ((dy < 0) == (pErr > 0) ? 1 : 0) : -1, fight);
            }
            if (Math.abs((double) pitch) < 89.0 && Math.abs(pPitch) < 89.0) {
                double dp = Math.abs((double) pitch - pPitch);
                if (dp > MIN_DELTA && dp < MAX_DELTA) {
                    sample(dp, -1, fight);
                }
            }
            if (!fight || Double.isNaN(step) || cusum.s < cusum.h) {
                return false;
            }
            double b = bias();
            if (Double.isNaN(b) || b < minBias) {
                return false;
            }
            cusum.s = 0.0;
            return true;
        }
    }
}
