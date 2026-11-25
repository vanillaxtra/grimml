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
                java.util.List.of("&7Details: &f%verbose%"), "/tp %player%", true, "", 1f, 1f, 0.05, java.util.List.of(), hook, null, 0, 0);
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
        punishments(ph);
        // punishment delay: 0..0 runs right away, otherwise a whole number of seconds inside the range (either order)
        check(GrimBridge.punishDelaySeconds(s) == 0, "no delay");
        var d = new GrimBridge.Settings(s.prefix(), s.format(), s.hover(), s.clickCommand(), true, "", 1f, 1f, 0.05,
                java.util.List.of(), hook, null, 30, 10);
        for (int i = 0; i < 200; i++) {
            long w = GrimBridge.punishDelaySeconds(d);
            check(w >= 10 && w <= 30, "delay in range: " + w);
        }
        // update checker versions: numeric per part, tolerant of v prefixes and suffixes
        var V = (java.util.function.Function<String, dev.grimml.detect.update.Version>) dev.grimml.detect.update.Version::new;
        check(V.apply("1.0.10").compareTo(V.apply("1.0.9")) > 0, "1.0.10 > 1.0.9");
        check(V.apply("v1.2.0").compareTo(V.apply("1.2.0-SNAPSHOT")) == 0, "v prefix and -SNAPSHOT");
        check(V.apply("1.2").compareTo(V.apply("1.1.9")) > 0 && V.apply("garbage").toString().equals("0.0.0"), "short and bad versions");
        System.out.println("alert format: all checks passed");
    }

    /** Groups count their checks together, fire each threshold once on the way up, decay, and fill placeholders. */
    private static void punishments(java.util.Map<String, String> ph) {
        var at = new java.util.TreeMap<Integer, java.util.List<String>>();
        at.put(2, java.util.List.of("kick %player% Unfair advantage (%check%)"));
        at.put(3, java.util.List.of("/ban %player% x%group_vl%".substring(1)));
        var lunge = new Punishments.Group("instant-lunge", java.util.Set.of("instant lunge", "swap macro"), 0.5, at);
        var all = new Punishments.Group("all", java.util.Set.of("*"), 0.0, new java.util.TreeMap<>(java.util.Map.of(4, java.util.List.of("say all"))));
        var groups = java.util.List.of(lunge, all);
        check(lunge.covers("LungeRate") && lunge.covers("LungeHold") && lunge.covers("SwapOrder") && !lunge.covers("AxeHold"),
                "group covers short names");
        var pr = new Punishments();
        var id = java.util.UUID.randomUUID();
        long t = 1_000_000L;
        check(pr.flag(id, "LungeRate", groups, t).isEmpty(), "1st violation fires nothing");
        var f = pr.flag(id, "LungeHold", groups, t + 1000);
        check(f.size() == 1 && f.get(0).violations() == 2 && f.get(0).group() == lunge, "2nd violation fires the kick: " + f);
        f = pr.flag(id, "AxeHold", groups, t + 2000);
        check(f.isEmpty(), "AxeHold is not in instant-lunge, and 'all' is only at 3");
        f = pr.flag(id, "SwapOrder", groups, t + 3000);
        check(f.size() == 2 && f.get(0).violations() == 3 && f.get(1).violations() == 4, "ban at 3 and 'all' at 4: " + f);
        f = pr.flag(id, "SwapOrder", groups, t + 4000);
        check(f.isEmpty(), "no threshold fires twice on the way up");
        // decay 0.5/min: after 6 minutes 4 -> 1, the next violation reaches 2 again and kicks again
        f = pr.flag(id, "LungeRate", groups, t + 4000 + 6 * 60_000);
        check(f.size() == 1 && f.get(0).violations() == 2, "after decay the kick fires again: " + f);
        var pph = GrimBridge.punishmentPlaceholders(ph, new Punishments.Fired(lunge, 3, at.get(3)));
        check(pph.get("%commands%").equals("ban St<red>eve x3"), "commands placeholder: " + pph.get("%commands%"));
        var pw = new GrimBridge.Webhook(true, "", false, "**GrimML Punishment**", "#FF0000", "", "", "", "", false, false,
                java.util.List.of("**Punishment**: %group% at %group_vl% violations", "**Commands**: `%commands%`"));
        String body = GrimBridge.embed(pw, pph, "");
        check(body.contains("instant-lunge at 3 violations") && body.contains("ban St<red>eve x3")
                && body.contains("16711680"), "punishment webhook body: " + body);
        // the shipped config parses: every group has checks and numeric thresholds
        try (var in = FormatSelfCheck.class.getClassLoader().getResourceAsStream("config.yml")) {
            var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            check(cfg.getInt("config-version") == dev.grimml.detect.GrimMLDetector.CONFIG_VERSION,
                    "shipped config-version matches GrimMLDetector.CONFIG_VERSION");
            var gs = cfg.getConfigurationSection("punishments.groups");
            check(gs != null && gs.getKeys(false).size() >= 4, "default punishment groups");
            for (String g : gs.getKeys(false)) {
                check(!cfg.getStringList("punishments.groups." + g + ".checks").isEmpty(), g + " has checks");
                for (String k : cfg.getConfigurationSection("punishments.groups." + g + ".at-violations").getKeys(false)) {
                    Integer.parseInt(k);
                }
            }
            check(!cfg.getBoolean("punishments.enabled", true), "punishments ship off");
            check(cfg.getStringList("webhook.punishments.content").size() > 2, "punishment webhook lines");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        System.out.println("punishments: all checks passed");
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
