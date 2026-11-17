package dev.grimml.detect.grim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Grim's alert format mixes '&' codes with MiniMessage hover tags; both must render, nothing raw may leak. */
public final class FormatSelfCheck {

    public static void main(String[] args) {
        String grimStyle = "&bGrim &8» &f%player% &bfailed &f<hover:show_text:\"%check%*\n&7Description: &f%desc%\">%check%*</hover> &f(x&c1&f) &7%verbose%"
                .replace("%player%", "Steve").replace("%check%", "MLKillAura").replace("%desc%", "ML model")
                .replace("%verbose%", "6/10 windows (60%, line 20%)");
        Component c = GrimBridge.colour(grimStyle);
        String plain = PlainTextComponentSerializer.plainText().serialize(c);
        check(plain.equals("Grim » Steve failed MLKillAura* (x1) 6/10 windows (60%, line 20%)"), "plain text: " + plain);
        check(!plain.contains("<") && !plain.contains("&"), "raw tags or codes leaked: " + plain);
        check(hasHover(c), "hover text missing");
        check(GrimBridge.legacyToMini("&#12ab34x").equals("<#12ab34>x"), "hex colour");

        // GrimML's own alert line: short text, details only on hover, values can never inject tags
        var hook = new GrimBridge.Webhook(true, "", false, "**GrimML Alert**", "#00FFFF", "https://crafthead.net/helm/%uuid%",
                "", "GrimML v%grimml_version%", "", true, true, java.util.List.of("**Player**: `%player%`", "**Check**: %check_name%"));
        var s = new GrimBridge.Settings("&bGrimML &8»", "%prefix% &f%player% &7flagged &b%check% &8(&f%confidence%%&8) &7x%vl%",
                java.util.List.of("&7Details: &f%verbose%"), "/tp %player%", true, "", 1f, 1f, 0.05, java.util.Map.of(), hook);
        var ph = new java.util.HashMap<String, String>(java.util.Map.of("%player%", "St<red>eve", "%check%", "Kill Aura",
                "%check_name%", "MLKillAura", "%confidence%", "74", "%vl%", "2", "%verbose%", "7 of 24 windows",
                "%uuid%", "00000000-0000-0000-0000-000000000001", "%grimml_version%", "1.1.0"));
        Component line = GrimBridge.message(s, ph);
        String text = PlainTextComponentSerializer.plainText().serialize(line);
        check(text.equals("GrimML » St<red>eve flagged Kill Aura (74%) x2"), "alert line: " + text);
        check(hasHover(line), "alert hover missing");
        check(line.clickEvent() != null && line.clickEvent().value().equals("/tp St<red>eve"), "click command");
        String body = GrimBridge.embed(hook, ph, "7 of 24 windows");
        check(body.contains("\"title\":\"**GrimML Alert**\"") && body.contains("crafthead.net/helm/00000000")
                && body.contains("\"name\":\"Verbose\"") && body.contains("Confidence 74%"), "webhook body: " + body);
        System.out.println("alert format: all checks passed");
    }

    private static boolean hasHover(Component c) {
        if (c.hoverEvent() != null && c.hoverEvent().action() == HoverEvent.Action.SHOW_TEXT) {
            return true;
        }
        return c.children().stream().anyMatch(FormatSelfCheck::hasHover);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
