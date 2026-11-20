package dev.grimml.detect.features;

/**
 * The three combo repeat checks for one player (exact port of Python spearmace.LiveCombo). Not thread safe: one scan
 * thread feeds it. Each feed returns alert bits: {@link #STUN}, {@link #SLAM}, {@link #WEB} (0 = none).
 *
 * A combo starts with a hit with the axe and ends with the swap away from it plus what follows:
 * <ul>
 * <li>STUN (StunRepeat): axe swapped in, axe hit, swapped to another weapon, follow-up hit on the same target.</li>
 * <li>SLAM (SlamRepeat): axe hit, swapped to the mace, mace hit on the same target (stun slam).</li>
 * <li>WEB (WebRepeat): axe hit, swapped to cobwebs, cobweb placed (stun web).</li>
 * </ul>
 * Each combo is written down as the client ticks between its steps (its signature): swap-in to axe hit, axe hit to
 * swap away, swap away to the follow-up; -1 where a step is missing (no swap-in for a slam or web, no follow-up hit
 * for a stun). A hand does not do the same chain to the tick every time; a macro with fixed delays does. {@code count}
 * combos of one kind with the same signature among that kind's last {@code of} alert, and that kind's list restarts.
 * Needs client ticks (1.21.2+ clients): combos with a step at client tick 0 are not written down. All times are
 * integer nanoseconds.
 */
public final class ComboRepeat {

    public static final int AXE = 2, MACE = 3, COBWEB = 15;
    public static final int STUN = 1, SLAM = 2, WEB = 4;
    static final long SWAP_IN_NS = 500_000_000L, OUT_NS = 1_000_000_000L, HIT_NS = 500_000_000L,
            PLACE_NS = 1_000_000_000L;
    private static final long NONE = Long.MIN_VALUE;

    private final int count, of;
    private final long[][] lists = new long[3][];
    private final int[] sizes = new int[3];
    /** Per kind (index 0 stun, 1 slam, 2 web): top signature share and the signature at its last alert. */
    public final double[] share = new double[3];
    public final int[][] sig = new int[3][];
    private final int[] combos = new int[3];

    private int swapTo = -1;
    private long swapNs;
    private int swapCt;
    private int stage;      // 0 none, 1 after the axe hit, 2 wait follow-up hit, 3 wait mace hit, 4 wait place
    private int inCt = -1;  // swap-in client tick (-1 none)
    private long axeNs, outNs;
    private int axeCt, outCt, target;
    /** Event time of the last event fed, so the scanner feeds each event once. */
    public long lastNanos = Long.MIN_VALUE;

    public ComboRepeat(int count, int of) {
        this.count = count;
        this.of = Math.max(1, of);
        for (int k = 0; k < 3; k++) {
            lists[k] = new long[this.of];
        }
    }

    /** Defaults as in config.yml (and Python LiveCombo()). */
    public ComboRepeat() {
        this(8, 10);
    }

    public static int index(int bit) {
        return bit == STUN ? 0 : bit == SLAM ? 1 : 2;
    }

    public int combos(int bit) {
        return combos[index(bit)];
    }

    private static long encode(int a, int b, int c) {
        return ((long) ((a + 1) & 0x1FFFFF) << 42) | ((long) ((b + 1) & 0x1FFFFF) << 21) | ((c + 1) & 0x1FFFFF);
    }

    private static int[] decode(long v) {
        return new int[]{(int) ((v >>> 42) & 0x1FFFFF) - 1, (int) ((v >>> 21) & 0x1FFFFF) - 1, (int) (v & 0x1FFFFF) - 1};
    }

    private int add(int bit, long s) {
        stage = 0;
        if (s == NONE) {
            return 0;
        }
        int k = index(bit);
        combos[k]++;
        long[] l = lists[k];
        if (sizes[k] == of) {
            System.arraycopy(l, 1, l, 0, of - 1);
            sizes[k]--;
        }
        l[sizes[k]++] = s;
        int n = sizes[k], best = 0, bestAt = 0;
        for (int i = 0; i < n; i++) {
            int c = 0;
            for (int j = 0; j < n; j++) {
                if (l[j] == l[i]) {
                    c++;
                }
            }
            if (c > best) {
                best = c;
                bestAt = i;
            }
        }
        if (best >= count) {
            share[k] = (double) best / n;
            sig[k] = decode(l[bestAt]);
            sizes[k] = 0;
            return bit;
        }
        return 0;
    }

    /** (swap-in to axe hit, axe hit to swap away, swap away to end) in client ticks; NONE at client tick 0. */
    private long gaps(int endCt) {
        if (axeCt == 0 || outCt == 0 || endCt == 0 || inCt == 0) {
            return NONE;
        }
        return encode(inCt > 0 ? axeCt - inCt : -1, outCt - axeCt, endCt > 0 ? endCt - outCt : -1);
    }

    private int closeStun() {
        return add(STUN, gaps(-1));
    }

    public int swap(long nanos, int toCat, int fromCat, int ct) {
        int out = 0;
        if (stage == 1) {
            if (fromCat == AXE && nanos - axeNs <= OUT_NS) {
                outNs = nanos;
                outCt = ct;
                stage = toCat == MACE ? 3 : toCat == COBWEB ? 4 : inCt >= 0 ? 2 : 0;
            } else {
                stage = 0;
            }
        } else if (stage == 2) {
            out |= closeStun();
        } else {
            stage = 0;
        }
        swapTo = toCat;
        swapNs = nanos;
        swapCt = ct;
        return out;
    }

    public int hit(long nanos, int ct, int cat, int targetId) {
        int out = 0;
        if (stage == 2) {
            if (cat != AXE && targetId == target && nanos - outNs <= HIT_NS) {
                return add(STUN, gaps(ct));
            }
            out |= closeStun();
        } else if (stage == 3) {
            boolean ok = cat == MACE && targetId == target && nanos - axeNs <= HIT_NS;
            stage = 0;
            if (ok) {
                return add(SLAM, gaps(ct));
            }
        } else {
            stage = 0;
        }
        if (cat == AXE) {
            inCt = swapTo == AXE && nanos - swapNs <= SWAP_IN_NS ? swapCt : -1;
            axeNs = nanos;
            axeCt = ct;
            target = targetId;
            stage = 1;
        }
        return out;
    }

    public int place(long nanos, int ct) {
        if (stage == 4) {
            boolean ok = swapTo == COBWEB && nanos - outNs <= PLACE_NS;
            stage = 0;
            return ok ? add(WEB, gaps(ct)) : 0;
        }
        if (stage == 2) {
            return closeStun();
        }
        if (stage != 1) { // placing while holding the axe keeps the axe hit
            stage = 0;
        }
        return 0;
    }
}
