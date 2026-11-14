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
