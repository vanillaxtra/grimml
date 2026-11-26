package dev.grimml.detect.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * Picks which fights telemetry sends, so the data is small but keeps what matters for training.
 *
 * A fight is one player's scored windows with no gap longer than gapMs. When it ends it waits holdMinutes (so a late
 * alert or a staff label still reaches it), then gets a band:
 *   high    an alert came during it                        kept
 *   unsure  at least unsureShare of its windows passed a   kept
 *           model's window threshold, but no alert
 *   low     the rest                                       kept at a rate that gives about balance x (high + unsure)
 * On top of that every fight has a randomShare chance of being kept whatever its band ("rand": true), which is the
 * only unbiased sample. Fights of labelled or trusted players are always kept.
 *
 * Each kept fight is sent as one "fight" line (band, keep = the chance it had of being kept, rand, label) followed by its
 * window lines, which carry the same "g". Training weights a fight by 1 / keep to get the real mix back.
 * A player has at most perPlayerHour kept fights per hour (labelled fights do not count), so one player can't fill the data.
 */
final class FightSampler {

    record Sampling(double unsureShare, double balance, double randomShare, int perPlayerHour, int holdMinutes,
                    long gapMs) {
        Sampling {
            unsureShare = Math.max(0.01, Math.min(1.0, unsureShare));
            balance = Math.max(0.0, balance);
            randomShare = Math.max(0.0, Math.min(1.0, randomShare));
            perPlayerHour = Math.max(1, perPlayerHour);
            holdMinutes = Math.max(0, holdMinutes);
            gapMs = Math.max(2_000L, gapMs);
        }

        static Sampling defaults() {
            return new Sampling(0.25, 1.0, 0.03, 6, 5, 30_000L);
        }
    }

    static final long LABEL_MS = 30 * 60_000L;          // a label also applies to the player's next 30 minutes
    static final long MAX_HELD_BYTES = 32L * 1024 * 1024; // open + waiting fights; past this the oldest are decided early
    static final int MAX_FIGHT_WINDOWS = 150;            // 5 minutes of windows, then a new fight starts

    private static final class Fight {
        final long g;
        final String p;
        final long start;
        long last;
        long ended = -1;
        int windows;
        int flagged;
        boolean alerted;
        boolean trusted;
        String label;
        String kind;
        String by;
        boolean banned;
        long bytes;
        final List<String> lines = new ArrayList<>();
        final Map<String, Double> max = new LinkedHashMap<>();

        Fight(long g, String p, long start) {
            this.g = g;
            this.p = p;
            this.start = start;
            this.last = start;
        }
    }

    private record Label(String label, String kind, long until) {
    }

    private final Consumer<List<String>> out;
    private final Random rnd;
    private final Map<String, Fight> open = new HashMap<>();
    private final ArrayDeque<Fight> held = new ArrayDeque<>();
    private final Map<String, Label> labels = new HashMap<>();
    private final Map<String, Long> alertedAt = new HashMap<>(); // alerts with no fight yet (rule checks fire early)
    private final Map<String, ArrayDeque<Long>> kept = new HashMap<>();
    private Sampling s;
    private long nextG;
    private long heldBytes;
    private double highSeen;
    private double lowSeen;
    private final long[] seen = new long[3];
    private final long[] sent = new long[3];
    private long capped;

    FightSampler(Sampling s, Consumer<List<String>> out, Random rnd, long firstG) {
        this.s = s;
        this.out = out;
        this.rnd = rnd;
        this.nextG = firstG;
    }

    synchronized void settings(Sampling s) {
        this.s = s;
    }

    synchronized void clear() {
        open.clear();
        held.clear();
        heldBytes = 0;
    }

    // one scored window; line builds the window line for the fight's g
    synchronized void window(String p, long nowMs, boolean trusted, boolean flagged, Map<String, Double> scores,
                             LongFunction<String> line) {
        Fight f = open.get(p);
        if (f != null && (nowMs - f.last > s.gapMs() || f.windows >= MAX_FIGHT_WINDOWS)) {
            close(f, nowMs);
            f = null;
        }
        if (f == null) {
            f = new Fight(nextG++, p, nowMs);
            Long a = alertedAt.remove(p);
            f.alerted = a != null && nowMs - a <= s.gapMs();
            Label l = labels.get(p);
            if (l != null && l.until() >= nowMs) {
                apply(f, l);
            }
            open.put(p, f);
        }
        f.trusted |= trusted;
        f.last = nowMs;
        f.windows++;
        if (flagged) {
            f.flagged++;
        }
        for (var e : scores.entrySet()) {
            if (!Double.isNaN(e.getValue())) {
                f.max.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        String l = line.apply(f.g);
        f.lines.add(l);
        f.bytes += l.length() + 1;
        heldBytes += l.length() + 1;
    }

    // an alert marks the player's open fight, or the newest waiting one, or the next one if it starts within gapMs
    synchronized void alert(String p, long nowMs) {
        Fight f = open.get(p);
        if (f == null) {
            for (Iterator<Fight> it = held.descendingIterator(); it.hasNext(); ) {
                Fight h = it.next();
                if (h.p.equals(p)) {
                    f = h;
                    break;
                }
            }
        }
        if (f != null) {
            f.alerted = true;
        } else {
            alertedAt.put(p, nowMs);
        }
    }

    // a staff label: applies to the player's open and waiting fights and the next LABEL_MS; returns how many fights it reached
    synchronized int label(String p, String label, String kind, long nowMs) {
        Label l = new Label(label, kind, nowMs + LABEL_MS);
        labels.put(p, l);
        int n = 0;
        Fight f = open.get(p);
        if (f != null) {
            apply(f, l);
            n++;
        }
        for (Fight h : held) {
            if (h.p.equals(p)) {
                apply(h, l);
                n++;
            }
        }
        return n;
    }

    // the player was banned: their open and waiting fights are kept and marked
    synchronized int ban(String p) {
        int n = 0;
        Fight f = open.get(p);
        if (f != null) {
            f.banned = true;
            n++;
        }
        for (Fight h : held) {
            if (h.p.equals(p)) {
                h.banned = true;
                n++;
            }
        }
        return n;
    }

    private static void apply(Fight f, Label l) {
        f.label = l.label();
        f.kind = l.kind();
        f.by = "staff";
    }

    private void close(Fight f, long nowMs) {
        open.remove(f.p);
        f.ended = nowMs;
        held.addLast(f);
    }

    // scan thread, after every scan: ends quiet fights and decides the ones that waited long enough
    synchronized void tick(long nowMs) {
        for (Iterator<Fight> it = open.values().iterator(); it.hasNext(); ) {
            Fight f = it.next();
            if (nowMs - f.last > s.gapMs()) {
                it.remove();
                f.ended = nowMs;
                held.addLast(f);
            }
        }
        long hold = s.holdMinutes() * 60_000L;
        while (!held.isEmpty() && (held.peekFirst().ended + hold <= nowMs || heldBytes > MAX_HELD_BYTES)) {
            decide(held.removeFirst(), nowMs);
        }
        labels.values().removeIf(l -> l.until() < nowMs);
        alertedAt.values().removeIf(a -> nowMs - a > s.gapMs());
        kept.values().forEach(q -> {
            while (!q.isEmpty() && q.peekFirst() < nowMs - 3_600_000L) {
                q.removeFirst();
            }
        });
        kept.values().removeIf(ArrayDeque::isEmpty);
    }

    private void decide(Fight f, long nowMs) {
        heldBytes -= f.bytes;
        int band = f.alerted ? 0 : (f.flagged > 0 && f.flagged >= s.unsureShare() * f.windows ? 1 : 2);
        seen[band]++;
        highSeen *= 0.999;
        lowSeen *= 0.999;
        if (band == 2) {
            lowSeen++;
        } else {
            highSeen++;
        }
        if (f.label == null && f.trusted) {
            f.label = "legit";
            f.by = "trusted";
        }
        boolean forced = f.label != null || f.banned;
        double p = forced || band < 2 ? 1.0 : Math.min(1.0, s.balance() * highSeen / Math.max(1.0, lowSeen));
        double r = s.randomShare();
        double keep = 1.0 - (1.0 - p) * (1.0 - r);
        boolean rand = rnd.nextDouble() < r;
        boolean take = rand || rnd.nextDouble() < p;
        if (!take) {
            return;
        }
        ArrayDeque<Long> q = kept.computeIfAbsent(f.p, k -> new ArrayDeque<>());
        if (!"staff".equals(f.by) && !f.banned && q.size() >= s.perPlayerHour()) {
            capped++;
            return;
        }
        if (!"staff".equals(f.by)) {
            q.addLast(nowMs);
        }
        sent[band]++;
        List<String> lines = new ArrayList<>(f.lines.size() + 1);
        lines.add(fightLine(f, band, keep, rand));
        lines.addAll(f.lines);
        out.accept(lines);
    }

    private static String fightLine(Fight f, int band, double keep, boolean rand) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"fight\",\"g\":").append(f.g).append(",\"p\":\"").append(f.p).append("\",\"t\":")
                .append(f.start / 1000).append(",\"end\":").append(f.last / 1000).append(",\"windows\":").append(f.windows)
                .append(",\"flagged\":").append(f.flagged).append(",\"band\":\"")
                .append(band == 0 ? "high" : band == 1 ? "unsure" : "low").append("\",\"keep\":");
        Telemetry.number(sb, keep);
        sb.append(",\"rand\":").append(rand).append(",\"max\":{");
        boolean first = true;
        for (var e : f.max.entrySet()) {
            sb.append(first ? "" : ",").append('"').append(e.getKey()).append("\":");
            Telemetry.number(sb, e.getValue());
            first = false;
        }
        sb.append('}');
        if (f.banned) {
            sb.append(",\"banned\":true");
        }
        if (f.label != null) {
            sb.append(",\"label\":\"").append(f.label).append("\",\"by\":\"").append(f.by).append('"');
            if (f.kind != null) {
                sb.append(",\"kind\":\"").append(f.kind).append('"');
            }
        }
        return sb.append('}').toString();
    }

    synchronized String summary() {
        return "fights high " + sent[0] + "/" + seen[0] + ", unsure " + sent[1] + "/" + seen[1] + ", low " + sent[2] + "/"
                + seen[2] + " sent" + (capped > 0 ? ", " + capped + " over the per-player cap" : "")
                + ", " + (open.size() + held.size()) + " waiting";
    }
}
