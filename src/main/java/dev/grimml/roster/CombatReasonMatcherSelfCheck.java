package dev.grimml.roster;

import java.util.List;

public final class CombatReasonMatcherSelfCheck {

    public static void main(String[] args) {
        CombatReasonMatcher m = new CombatReasonMatcher(List.of("killaura", "aim assist", "reach"));
        check(m.matches("Killaura 10m"), true);
        check(m.matches("aim assist blatant"), true);
        check(m.matches("Reach hacks"), true);
        check(m.matches("advertising discord"), false);
        check(m.matches("spam"), false);
        check(m.matches(""), false);
        check(new CombatReasonMatcher(List.of()).matches("killaura"), false);
        System.out.println("combat reason matcher self-check passed");
    }

    private static void check(boolean got, boolean want) {
        if (got != want) {
            throw new AssertionError("matcher got " + got + " want " + want);
        }
    }
}
