package dev.grimml.inference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// decayed per player sum of window probabilities; same rule as eval/report.py player_aggregate
public final class ViolationBuffer {

    private static final long IDLE_NANOS = 30L * 60L * 1_000_000_000L;

    public static final class State {
        public double score;
        public int windows;
        public double lastProba;
        long lastNanos;
        long lastAlertNanos;
    }

    private final Map<Long, State> players = new ConcurrentHashMap<>();
    private final double decay;
    private final double alertScore;
    private final long cooldownNanos;
    private long lastSweep;

    public ViolationBuffer(double decay, double alertScore, int cooldownSeconds) {
        this.decay = decay;
        this.alertScore = alertScore;
        this.cooldownNanos = cooldownSeconds * 1_000_000_000L;
    }

    // returns true when this window pushes the player over the alert score outside the cooldown
    public boolean add(long anon, double proba, long nanos) {
        State s = players.computeIfAbsent(anon, k -> new State());
        s.score = s.score * decay + proba;
        s.windows++;
        s.lastProba = proba;
        s.lastNanos = nanos;
        sweep(nanos);
        if (s.score < alertScore) {
            return false;
        }
        if (s.lastAlertNanos != 0L && nanos - s.lastAlertNanos < cooldownNanos) {
            return false;
        }
        s.lastAlertNanos = nanos;
        return true;
    }

    public State get(long anon) {
        return players.get(anon);
    }

    public Map<Long, State> view() {
        return players;
    }

    private void sweep(long nanos) {
        if (nanos - lastSweep < 60_000_000_000L) {
            return;
        }
        lastSweep = nanos;
        players.values().removeIf(s -> nanos - s.lastNanos > IDLE_NANOS);
    }
}
