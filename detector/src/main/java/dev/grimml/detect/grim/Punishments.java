package dev.grimml.detect.grim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is the punishments class for the GrimMLDetector.
 * It is used to count the violations of the ML checks and return the console commands for every violation count it just reached.
 * 
 * Punishment groups where each group counts the violations of its checks together (minus decay) and returns the
 * console commands for every violation count it just reached. This is used to punish the players.
 * 
 */
public final class Punishments {

    public record Group(String name, Set<String> checks, double decayPerMinute, NavigableMap<Integer, List<String>> at) {

        // checks must be lower case.
        public boolean covers(String checkName) {
            return checks.contains("*") || checks.contains(checkName.toLowerCase(Locale.ROOT))
                    || checks.contains(GrimBridge.friendly(checkName).toLowerCase(Locale.ROOT));
        }
    }

    public record Fired(Group group, int violations, List<String> commands) {
    }

    // per player
    private final Map<UUID, Map<String, double[]>> counts = new ConcurrentHashMap<>();

    // adds one violation of checkName to every group that covers it and returns the thresholds reached.
    public List<Fired> flag(UUID player, String checkName, List<Group> groups, long nowMs) {
        List<Fired> out = new ArrayList<>();
        Map<String, double[]> mine = counts.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        synchronized (mine) {
            for (Group g : groups) {
                if (!g.covers(checkName)) {
                    continue;
                }
                double[] c = mine.computeIfAbsent(g.name(), k -> new double[2]);
                double before = c[0];
                if (c[1] != 0 && g.decayPerMinute() > 0) { // decays the violations by the decay per minute
                    before = Math.max(0.0, before - (nowMs - c[1]) / 60_000.0 * g.decayPerMinute());
                }
                double after = before + 1.0;
                c[0] = after;
                c[1] = nowMs;
                // the rounded count, like %vl% in alerts: with decay the raw count sits just under a whole number
                // (1.99 after two quick flags), so comparing raw values would miss thresholds. A flag adds exactly 1,
                // so the rounded count goes up by exactly 1 and each threshold fires once on the way up.
                int reached = (int) Math.round(after);
                List<String> cmds = g.at().get(reached);
                if (cmds != null) {
                    out.add(new Fired(g, reached, cmds));
                }
            }
        }
        return out;
    }

    public double violations(UUID player, String group) {
        Map<String, double[]> mine = counts.get(player);
        double[] c = mine == null ? null : mine.get(group);
        return c == null ? 0.0 : c[0];
    }

    public void forget(UUID player) {
        counts.remove(player);
    }
}
