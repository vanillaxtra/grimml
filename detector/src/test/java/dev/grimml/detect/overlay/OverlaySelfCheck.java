package dev.grimml.detect.overlay;

import java.util.List;

/** What the staff overlay shows: flagged lines first, watching lines while a fight builds up, old ones drop off. */
public final class OverlaySelfCheck {

    private static final long KEEP = 5 * 60_000L;
    private static final long LIVE = 30_000L;

    public static void main(String[] args) {
        OverlayLines l = new OverlayLines();
        long t = 1_000_000L;

        // a fight with no flagged windows shows nothing
        l.live("MLKillAura", 5, 0, 0.2, t);
        check(l.lines(t, KEEP, LIVE, true, 4).isEmpty(), "no flagged windows, no line");

        // flagged windows in a fight that has not alerted: a watching line with the live counts
        l.live("MLKillAura", 8, 3, 0.45, t);
        List<OverlayLines.Line> ls = l.lines(t, KEEP, LIVE, true, 4);
        check(ls.size() == 1 && ls.get(0).kind() == OverlayLines.Kind.WATCHING && ls.get(0).flagged() == 3
                && ls.get(0).windows() == 8, "watching line: " + ls);
        check(l.lines(t, KEEP, LIVE, false, 4).isEmpty(), "show-watching off hides it");

        // the alert replaces it with the fight's verdict, and later live windows do not overwrite it
        l.alert("MLKillAura", 0.74, 12, 30, t + 2000);
        l.live("MLKillAura", 1, 1, 0.9, t + 4000);
        ls = l.lines(t + 4000, KEEP, LIVE, true, 4);
        check(ls.size() == 1 && ls.get(0).kind() == OverlayLines.Kind.FLAGGED && ls.get(0).flagged() == 12
                && ls.get(0).windows() == 30 && ls.get(0).count() == 1, "flagged line keeps the verdict: " + ls);

        // rule checks count alerts, newest alert first
        l.alert("SwapOrder", 1.0, -1, -1, t + 6000);
        l.alert("SwapOrder", 1.0, -1, -1, t + 7000);
        ls = l.lines(t + 7000, KEEP, LIVE, true, 4);
        check(ls.get(0).kind() == OverlayLines.Kind.RULE && ls.get(0).count() == 2, "rule line first, x2: " + ls);
        check(l.lines(t + 7000, KEEP, LIVE, true, 1).size() == 1, "max lines");

        // a watching line goes away when the fight is judged (counts reset) or goes quiet
        l.live("MLAimAssist", 10, 4, 0.5, t + 7000);
        check(l.lines(t + 7000, KEEP, LIVE, true, 4).size() == 3, "aim assist watching");
        l.live("MLAimAssist", 0, 0, 0.0, t + 9000);
        check(l.lines(t + 9000, KEEP, LIVE, true, 4).size() == 2, "judged fight drops the watching line");
        l.live("MLTriggerbot", 6, 2, 0.4, t + 9000);
        check(l.lines(t + 9000 + LIVE + 1, KEEP, LIVE, true, 4).size() == 2, "quiet fight drops the watching line");

        // everything is gone after keep-minutes
        check(!l.prune(t + 7000 + KEEP, KEEP, LIVE), "still inside keep");
        check(l.prune(t + 7000 + KEEP + 1, KEEP, LIVE), "all pruned after keep");

        // rendering: MiniMessage tags filled in, one JSON component with a line per check
        var s = new Overlay.Settings(true, 0.55f, 0.8f, 4, KEEP, true, false, 0x40000000,
                "<red><check></red> <white><confidence>%</white> <gray><flagged>/<windows>",
                "<yellow><check></yellow> <white><confidence>%</white> <gray><flagged>/<windows>",
                "<red><check></red> <white><confidence>%</white> <gray>x<count>");
        Overlay o = new Overlay(s, id -> null, id -> null, c -> c.equals("MLKillAura") ? "Kill <Aura>" : c, () -> 1);
        String json = o.render(List.of(
                new OverlayLines.Line(OverlayLines.Kind.FLAGGED, "MLKillAura", 0.74, 12, 30, 1),
                new OverlayLines.Line(OverlayLines.Kind.RULE, "SwapOrder", 1.0, -1, -1, 2)), s);
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json));
        check(plain.equals("Kill <Aura> 74% 12/30\nSwapOrder 100% x2"), "rendered text: " + plain);
        check(json.contains("red") && json.contains("gray"), "colours kept: " + json);
        check(o.render(List.of(), s) == null, "nothing to show renders null");
        System.out.println("overlay: all checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
