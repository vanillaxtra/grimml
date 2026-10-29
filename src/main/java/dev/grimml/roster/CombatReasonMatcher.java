package dev.grimml.roster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// substring match on a lowered ban reason empty keyword list matches nothing
public final class CombatReasonMatcher {

    private final String[] keys;

    public CombatReasonMatcher(List<String> keywords) {
        List<String> clean = new ArrayList<>();
        if (keywords != null) {
            for (String k : keywords) {
                if (k == null) {
                    continue;
                }
                String s = k.toLowerCase(Locale.ROOT).trim();
                if (!s.isEmpty()) {
                    clean.add(s);
                }
            }
        }
        this.keys = clean.toArray(String[]::new);
    }

    public boolean matches(String reason) {
        if (keys.length == 0 || reason == null || reason.isEmpty()) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (r.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
