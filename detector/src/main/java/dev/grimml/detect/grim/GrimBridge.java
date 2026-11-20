package dev.grimml.detect.grim;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.FlagEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Turns a GrimML flag into everything a staff member sees:
 *  - Grim's FlagEvent (other Grim add-ons can see and cancel it),
 *  - one short chat line with a confidence %, details on hover, to players allowed and opted in,
 *  - an optional sound ping (permission grimml.alerts.sound),
 *  - a Discord webhook laid out exactly like Grim's own (discord.yml),
 *  - optional punishment commands at violation counts (per group of checks), each also posted to the webhook.
 * Called from the scan thread; chat, sounds and commands run on the main thread, webhooks on their own thread.
 */
public final class GrimBridge {

    public record Settings(
            String prefix,
            String format,
            List<String> hover,
            String clickCommand,
            boolean consoleAlerts,
            String sound,
            float soundVolume,
            float soundPitch,
            double decayPerMinute,
            List<Punishments.Group> punishments,
            Webhook webhook,
            Webhook punishmentWebhook // null = punishments are not posted
    ) {
    }

    /** Field for field the same as Grim's discord.yml, so ML alerts look like Grim's own. */
    public record Webhook(boolean enabled, String url, boolean useGrimWebhook, String title, String color,
                          String thumbnailUrl, String imageUrl, String footerText, String footerUrl,
                          boolean includeTimestamp, boolean includeVerbose, List<String> content) {
    }

    /** Short names people understand, shown instead of the internal check name. */
    private static final Map<String, String> FRIENDLY = Map.ofEntries(
            Map.entry("MLKillAura", "Kill Aura"),
            Map.entry("MLAimAssist", "Aim Assist"),
            Map.entry("MLTriggerbot", "Triggerbot"),
            Map.entry("MLSilentAim", "Silent Aim"),
            Map.entry("AimGrid", "Aim Assist"),
            Map.entry("LungeRate", "Instant Lunge"),
            Map.entry("LungeHold", "Instant Lunge"),
            Map.entry("SwapOrder", "Swap Macro"),
            Map.entry("AxeHold", "Shield Breaker"),
            Map.entry("StunRepeat", "Stun Macro"),
            Map.entry("SlamRepeat", "Stun Slam"),
            Map.entry("WebRepeat", "Stun Web"));

    private static final String BACKTICK_REPLACEMENT = "ˋ"; // Grim's default backtick-replacement-char

    private final Plugin plugin;
    private final Logger log;
    private final Predicate<Player> receives;
    private final Map<UUID, Map<String, MlCheck>> checks = new ConcurrentHashMap<>();
    /** Kept when a player leaves, so relogging does not reset punishment counts (they decay instead). */
    private final Punishments punish = new Punishments();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService webhookThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100), r -> {
        Thread t = new Thread(r, "GrimML-webhook");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private volatile Settings settings;

    public GrimBridge(Plugin plugin, Settings settings, Predicate<Player> receives) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.settings = settings;
        this.receives = receives;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    public List<Punishments.Group> groups() {
        return settings.punishments();
    }

    public double groupViolations(UUID player, String group) {
        return punish.violations(player, group);
    }

    public void forget(UUID player) {
        checks.remove(player);
    }

    public void shutdown() {
        webhookThread.shutdownNow();
    }

    public static String friendly(String checkName) {
        return FRIENDLY.getOrDefault(checkName, checkName);
    }

    private static GrimAbstractAPI api() {
        try {
            return GrimAPIProvider.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scan thread. */
    public void flag(UUID uuid, String playerName, String checkName, String description, double confidence, String verbose) {
        Settings s = settings;
        MlCheck check = checks.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(checkName, k -> new MlCheck(checkName, description, s.decayPerMinute()));
        if (!check.isEnabled()) {
            return;
        }
        GrimAbstractAPI api = api();
        GrimUser user = api == null ? null : api.getGrimUser(uuid);
        if (user != null) {
            try {
                if (api.getEventBus().get(FlagEvent.class).fire(user, check, verbose)) {
                    return; // another Grim add-on cancelled it
                }
            } catch (Throwable t) {
                log.fine("FlagEvent failed: " + t);
            }
        }
        long now = System.currentTimeMillis();
        int vl = (int) Math.round(check.flag(now));
        List<Punishments.Fired> fired = s.punishments().isEmpty() ? List.of() : punish.flag(uuid, checkName, s.punishments(), now);
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, confidence)) * 100);

        Map<String, String> ph = new HashMap<>();
        ph.put("%player%", playerName);
        ph.put("%uuid%", uuid.toString());
        ph.put("%check%", friendly(checkName));
        ph.put("%check_name%", checkName);
        ph.put("%description%", description);
        ph.put("%confidence%", Integer.toString(pct));
        ph.put("%vl%", Integer.toString(vl));
        ph.put("%violations%", Integer.toString(vl));
        ph.put("%verbose%", verbose);
        ph.put("%ping%", user == null ? "?" : Integer.toString(user.getTransactionPing()));
        ph.put("%version%", user == null ? "?" : nz(user.getVersionName()));
        ph.put("%brand%", user == null ? "?" : nz(user.getBrand()));
        ph.put("%grimml_version%", plugin.getPluginMeta().getVersion());

        Bukkit.getScheduler().runTask(plugin, () -> {
            ph.put("%tps%", String.format("%.2f", Math.min(20.0, Bukkit.getTPS()[0])));
            Component msg = message(s, ph);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (receives.test(p)) {
                    p.sendMessage(msg);
                    if (!s.sound().isBlank() && p.hasPermission("grimml.alerts.sound")) {
                        p.playSound(p.getLocation(), s.sound(), s.soundVolume(), s.soundPitch());
                    }
                }
            }
            if (s.consoleAlerts()) {
                Bukkit.getConsoleSender().sendMessage(msg);
            }
            Webhook w = s.webhook();
            String hook = !w.url().isBlank() ? w.url() : (w.useGrimWebhook() ? grimString(api(), "webhook", "") : "");
            if (w.enabled() && hook.startsWith("https://")) {
                String body = embed(w, ph, verbose);
                webhookThread.execute(() -> post(hook, body));
            }
            for (Punishments.Fired f : fired) {
                Map<String, String> pph = punishmentPlaceholders(ph, f);
                for (String c : f.commands()) {
                    String cmd = apply(c, pph, false);
                    log.info("punishment " + f.group().name() + " at " + f.violations() + " violations: " + cmd);
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (Exception e) {
                        log.warning("punishment command failed: " + cmd + " (" + e.getMessage() + ")");
                    }
                }
                Webhook pw = s.punishmentWebhook();
                if (pw != null && hook.startsWith("https://")) {
                    String body = embed(pw, pph, verbose);
                    webhookThread.execute(() -> post(hook, body));
                }
            }
        });
    }

    /** The alert placeholders plus %group% %group_vl% and %commands% (the commands run, filled in, joined by "; "). */
    static Map<String, String> punishmentPlaceholders(Map<String, String> ph, Punishments.Fired f) {
        Map<String, String> out = new HashMap<>(ph);
        out.put("%group%", f.group().name());
        out.put("%group_vl%", Integer.toString(f.violations()));
        List<String> ran = new java.util.ArrayList<>();
        for (String c : f.commands()) {
            ran.add(apply(c, out, false));
        }
        out.put("%commands%", ran.isEmpty() ? "(none)" : String.join("; ", ran));
        return out;
    }

    private static String nz(String s) {
        return s == null ? "?" : s;
    }

    /** The chat line: format text, with the hover lines and click command attached to all of it. */
    static Component message(Settings s, Map<String, String> ph) {
        Map<String, String> safe = new HashMap<>(ph);
        safe.replaceAll((k, v) -> MiniMessage.miniMessage().escapeTags(v)); // inserted values can never open a tag
        safe.put("%prefix%", s.prefix());
        Component line = colour(apply(s.format(), safe, false));
        if (!s.hover().isEmpty()) {
            line = line.hoverEvent(HoverEvent.showText(colour(apply(String.join("\n", s.hover()), safe, false))));
        }
        if (!s.clickCommand().isBlank()) {
            line = line.clickEvent(ClickEvent.runCommand(apply(s.clickCommand(), ph, false)));
        }
        return line;
    }

    private static String apply(String text, Map<String, String> ph, boolean discord) {
        String out = text;
        for (var e : ph.entrySet()) {
            String v = discord ? e.getValue().replace("`", BACKTICK_REPLACEMENT) : e.getValue();
            out = out.replace(e.getKey(), v);
        }
        return out;
    }

    private static String grimString(GrimAbstractAPI api, String key, String fallback) {
        if (api == null) {
            return fallback;
        }
        try {
            String v = api.getConfigManager().getStringElse(key, fallback);
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---- colours: '&' codes and MiniMessage tags mixed, like Grim's messages

    private static final String[] LEGACY_TAGS = new String[128];

    static {
        String[] colours = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < 16; i++) {
            LEGACY_TAGS["0123456789abcdef".charAt(i)] = "<" + colours[i] + ">";
        }
        LEGACY_TAGS['k'] = "<obfuscated>";
        LEGACY_TAGS['l'] = "<bold>";
        LEGACY_TAGS['m'] = "<strikethrough>";
        LEGACY_TAGS['n'] = "<underlined>";
        LEGACY_TAGS['o'] = "<italic>";
        LEGACY_TAGS['r'] = "<reset>";
    }

    /** Turns '&' codes into MiniMessage tags, then parses once. */
    public static Component colour(String text) {
        return MiniMessage.miniMessage().deserialize(legacyToMini(text));
    }

    static String legacyToMini(String s) {
        StringBuilder out = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(i + 1));
                if (k == '#' && i + 7 < s.length() && s.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    out.append("<#").append(s, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                if (k < 128 && LEGACY_TAGS[k] != null) {
                    out.append(LEGACY_TAGS[k]);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    // ---- Discord: the same embed Grim's DiscordManager sends

    static String embed(Webhook w, Map<String, String> ph, String verbose) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", apply(w.title(), ph, true));
        embed.addProperty("description", apply(String.join("\n", w.content()), ph, true));
        try {
            embed.addProperty("color", java.awt.Color.decode(w.color()).getRGB() & 0xFFFFFF);
        } catch (NumberFormatException e) {
            embed.addProperty("color", 0x00FFFF);
        }
        if (!w.thumbnailUrl().isBlank()) {
            JsonObject t = new JsonObject();
            t.addProperty("url", apply(w.thumbnailUrl(), ph, false));
            embed.add("thumbnail", t);
        }
        if (!w.imageUrl().isBlank()) {
            JsonObject t = new JsonObject();
            t.addProperty("url", apply(w.imageUrl(), ph, false));
            embed.add("image", t);
        }
        if (!w.footerText().isBlank() || !w.footerUrl().isBlank()) {
            JsonObject f = new JsonObject();
            f.addProperty("text", apply(w.footerText(), ph, true));
            if (!w.footerUrl().isBlank()) {
                f.addProperty("icon_url", apply(w.footerUrl(), ph, false));
            }
            embed.add("footer", f);
        }
        if (w.includeTimestamp()) {
            embed.addProperty("timestamp", Instant.now().toString());
        }
        if (w.includeVerbose() && !verbose.isBlank()) {
            JsonObject field = new JsonObject();
            field.addProperty("name", "Verbose");
            field.addProperty("value", escapeMarkdown("Confidence " + ph.get("%confidence%") + "% - " + verbose));
            field.addProperty("inline", false);
            JsonArray fields = new JsonArray();
            fields.add(field);
            embed.add("fields", fields);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        return body.toString();
    }

    private static String escapeMarkdown(String s) {
        return s.replaceAll("([*_~`|>\\\\])", "\\\\$1");
    }

    /** Webhook thread: one post at a time; on Discord's rate limit, waits as long as it asks and tries once more. */
    private void post(String url, String body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 429 && attempt == 0) {
                    double wait = r.headers().firstValue("X-RateLimit-Reset-After")
                            .or(() -> r.headers().firstValue("Retry-After")).map(Double::parseDouble).orElse(1.0);
                    Thread.sleep((long) (Math.min(wait, 30.0) * 1000));
                    continue;
                }
                if (r.statusCode() >= 300) {
                    log.warning("webhook failed: HTTP " + r.statusCode());
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warning("webhook failed: " + e.getMessage());
                return;
            }
        }
    }
}
