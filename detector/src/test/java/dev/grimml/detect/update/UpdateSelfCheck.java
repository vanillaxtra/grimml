package dev.grimml.detect.update;

/** The update notice sends players to Modrinth whenever Modrinth has the newest version. */
public final class UpdateSelfCheck {

    public static void main(String[] args) {
        var m = new UpdateChecker.Remote(new Version("1.2.0"), "1.2.0", "modrinth");
        var gSame = new UpdateChecker.Remote(new Version("v1.2.0"), "1.2.0", "github");
        var gNewer = new UpdateChecker.Remote(new Version("1.2.1"), "1.2.1", "github");
        var gOlder = new UpdateChecker.Remote(new Version("1.1.9"), "1.1.9", "github");
        check(UpdateChecker.pick(m, gSame) == m, "same version on both: Modrinth");
        check(UpdateChecker.pick(m, gOlder) == m, "Modrinth newer: Modrinth");
        check(UpdateChecker.pick(m, gNewer) == gNewer, "only GitHub has the newest: GitHub");
        check(UpdateChecker.pick(m, null) == m, "GitHub down: Modrinth");
        check(UpdateChecker.pick(null, gSame) == gSame, "not on Modrinth yet: GitHub");
        check(UpdateChecker.pick(null, null) == null, "neither: nothing");
        check(new Version("1.0.10").compareTo(new Version("1.0.9")) > 0, "1.0.10 > 1.0.9");
        System.out.println("update checker: all checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
