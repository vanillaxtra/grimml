package dev.grimml.detect.grim;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.FlagEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Sends ML flags through Grim as far as its API allows:
 *  - fires Grim's FlagEvent (other Grim add-ons can see and cancel it),
 *  - alerts in Grim's own alert format and prefix to everyone with /grim alerts on,
 *  - Discord webhook in Grim's style (Grim's own webhook url, or one set in our config),
 *  - optional punishment commands at violation counts.
 * Called from the scan thread; chat and commands are moved to the main thread.
 */
public final class GrimBridge {

    public record Settings(
            String alertFormat,          // empty = use Grim's alerts-format
            boolean consoleAlerts,
            String webhookUrl,           // empty = Grim's webhook if useGrimWebhook
            boolean useGrimWebhook,
            double decayPerMinute,
            Map<Integer, List<String>> punishments
    ) {
    }

    private static final String FALLBACK_FORMAT = "%prefix% &f%player% &bfailed &f%check_name% &f(x&c%vl%&f) &7%verbose%";
    private static final String FALLBACK_PREFIX = "&bGrim &8»";

    private final Plugin plugin;
    private final Logger log;
    private final Map<UUID, Map<String, MlCheck>> checks = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Settings settings;

    public GrimBridge(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.settings = settings;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    public void forget(UUID player) {
        checks.remove(player);
    }

    private static GrimAbstractAPI api() {
        try {
            return GrimAPIProvider.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scan thread. */
    public void flag(UUID uuid, String playerName, String checkName, String description, String verbose) {
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
        double vl = check.flag(System.currentTimeMillis());
        int vlInt = (int) Math.round(vl);
        String format = s.alertFormat().isBlank() ? grimString(api, "alerts-format", FALLBACK_FORMAT) : s.alertFormat();
        String text = format
                .replace("%prefix%", grimString(api, "prefix", FALLBACK_PREFIX))
                .replace("%player%", playerName)
                .replace("%check_name%", checkName)
                .replace("%description%", description)
                .replace("%experimental%", "*")
                .replace("%vl%", Integer.toString(vlInt))
                .replace("%verbose%", verbose);
        Component msg = colour(text);

        Bukkit.getScheduler().runTask(plugin, () -> {
            GrimAbstractAPI a = api();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (a != null ? a.getAlertManager().hasAlertsEnabled(p) : p.hasPermission("grim.alerts")) {
                    p.sendMessage(msg);
                }
            }
            if (s.consoleAlerts()) {
                Bukkit.getConsoleSender().sendMessage(msg);
            }
            List<String> cmds = s.punishments().get(vlInt);
            if (cmds != null && check.isEnabled()) {
                for (String c : cmds) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c
                            .replace("%player%", playerName).replace("%check_name%", checkName).replace("%vl%", Integer.toString(vlInt)));
                }
            }
        });

        String hook = !s.webhookUrl().isBlank() ? s.webhookUrl() : (s.useGrimWebhook() ? grimString(api, "webhook", "") : "");
        if (!hook.isBlank()) {
            webhook(hook, playerName, checkName, vlInt, verbose);
        }
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

    private static Component colour(String text) {
        if (text.indexOf('<') >= 0 && text.indexOf('>') > text.indexOf('<') && text.indexOf('&') < 0) {
            return MiniMessage.miniMessage().deserialize(text);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    // Grim-style embed; only to the url the server owner configured (theirs or Grim's)
    private void webhook(String url, String player, String check, int vl, String verbose) {
        if (!url.startsWith("https://")) {
            return;
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Grim Alert");
        embed.addProperty("color", 0x00FFFF);
        embed.addProperty("timestamp", Instant.now().toString());
        embed.addProperty("description", "**Player**\n" + player + "\n**Check**\n" + check
                + "\n**Violations**\n" + vl + "\n**Verbose**\n" + verbose);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        http.sendAsync(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.discarding())
                .whenComplete((r, e) -> {
                    if (e != null || (r != null && r.statusCode() >= 300)) {
                        log.warning("webhook failed: " + (e != null ? e.getMessage() : "HTTP " + r.statusCode()));
                    }
                });
    }
}
