package dev.grimml.detect.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * This is the update checker for new GrimML releases.
 * It checks Modrinth and GitHub at the same time off the main thread, and the newest version wins.
 * If it is newer than this jar it prints a banner in the console, and players with grimml.update.notify get a
 * clickable message 2 seconds after they join (once a day each).
 *
 * Modrinth comes first: if Modrinth has the newest version the link goes there.
 * GitHub is only shown when Modrinth does not have that version yet or is down.
 */
public final class UpdateChecker implements Listener {
    private static final String MODRINTH_SLUG = "grimml";
    private static final String GITHUB_REPO = "vanillaxtra/grimml";
    private static final String PERMISSION = "grimml.update.notify";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // a release found on one site and the page to download it from
    record Remote(Version version, String label, String url) {
    }

    private final JavaPlugin plugin;
    private final Version current;
    private final String currentLabel;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile String latest = "";
    private volatile String url = "";
    private volatile boolean onModrinth;
    private volatile long lastCheck; // GitHub only allows 60 calls an hour, so joins re-check at most once an hour
    private final Map<UUID, LocalDate> notified = new HashMap<>(); // main thread only

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.currentLabel = plugin.getPluginMeta().getVersion();
        this.current = new Version(currentLabel);
        this.userAgent = "GrimML-UpdateChecker/" + currentLabel; // Modrinth rate limits requests without one
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        check().thenAccept(newer -> {
            if (newer) {
                banner();
            }
        });
    }

    // async, true when Modrinth or GitHub has a newer release than this jar (never throws)
    public CompletableFuture<Boolean> check() {
        lastCheck = System.currentTimeMillis();
        CompletableFuture<Remote> modrinth = modrinth();
        CompletableFuture<Remote> github = github();
        return modrinth.thenCombine(github, (m, g) -> {
            Remote pick = pick(m, g);
            boolean newer = pick != null && pick.version().compareTo(current) > 0;
            if (newer) {
                latest = pick.label();
                url = pick.url();
                onModrinth = pick == m;
            }
            return newer;
        }).exceptionally(ex -> {
            plugin.getLogger().fine("update check failed: " + ex.getMessage());
            return false;
        });
    }

    // picks the newest release, Modrinth wins a tie
    static Remote pick(Remote modrinth, Remote github) {
        if (modrinth == null) {
            return github;
        }
        if (github == null) {
            return modrinth;
        }
        return github.version().compareTo(modrinth.version()) > 0 ? github : modrinth;
    }

    // the newest Modrinth version (releases before betas)
    private CompletableFuture<Remote> modrinth() {
        return fetch("https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version", "application/json")
                .thenApply(body -> {
                    if (body == null) {
                        return null;
                    }
                    JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
                    JsonObject chosen = newest(versions, true); // releases first
                    if (chosen == null) {
                        chosen = newest(versions, false);
                    }
                    if (chosen == null) {
                        return null;
                    }
                    String number = chosen.get("version_number").getAsString().replaceAll("^[vV]", "");
                    return new Remote(new Version(number), number,
                            "https://modrinth.com/plugin/" + MODRINTH_SLUG + "/version/" + chosen.get("id").getAsString());
                });
    }

    // the latest GitHub release
    private CompletableFuture<Remote> github() {
        return fetch("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest", "application/vnd.github+json")
                .thenApply(body -> {
                    if (body == null) {
                        return null;
                    }
                    JsonObject rel = JsonParser.parseString(body).getAsJsonObject();
                    if (!rel.has("tag_name")) {
                        return null;
                    }
                    String tag = rel.get("tag_name").getAsString().replaceAll("^[vV]", "");
                    String page = rel.has("html_url") ? rel.get("html_url").getAsString()
                            : "https://github.com/" + GITHUB_REPO + "/releases/latest";
                    return new Remote(new Version(tag), tag, page);
                });
    }

    // returns the body, or null if anything fails (404 means nothing is published there yet)
    private CompletableFuture<String> fetch(String uri, String accept) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", accept)
                .header("User-Agent", userAgent)
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) {
                if (r.statusCode() != 404) {
                    plugin.getLogger().fine("update check: HTTP " + r.statusCode() + " for " + uri);
                }
                return (String) null;
            }
            return r.body();
        }).exceptionally(ex -> {
            plugin.getLogger().fine("update check failed for " + uri + ": " + ex.getMessage());
            return null;
        });
    }

    // the newest by version number, not by list order or upload date
    private static JsonObject newest(JsonArray versions, boolean releaseOnly) {
        JsonObject best = null;
        Version bestV = Version.ZERO;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!v.has("version_number") || !v.has("id")) {
                continue;
            }
            if (releaseOnly && !(v.has("version_type") && "release".equals(v.get("version_type").getAsString()))) {
                continue;
            }
            Version cand = new Version(v.get("version_number").getAsString());
            if (best == null || cand.compareTo(bestV) > 0) {
                best = v;
                bestV = cand;
            }
        }
        return best;
    }

    // the console banner on start
    private void banner() {
        var log = plugin.getLogger();
        log.info("----------------------------------------------------");
        log.info("  A GrimML update is available: " + currentLabel + " -> " + latest);
        log.info("  Download" + (onModrinth ? " on Modrinth: " : ": ") + url);
        log.info("----------------------------------------------------");
    }

    // the clickable message for staff
    private void notice(Player p) {
        if (latest.isEmpty() || !p.isOnline() || !p.hasPermission(PERMISSION)) {
            return;
        }
        p.sendMessage(MM.deserialize(
                "<aqua>GrimML <dark_gray>» <gray>Update available: <red><current></red> <dark_gray>→ <green><latest></green> "
                        + "<gold><click:open_url:'<url>'><hover:show_text:'<gray>Open <site>'><button></hover></click>",
                Placeholder.unparsed("current", currentLabel), Placeholder.unparsed("latest", latest),
                Placeholder.unparsed("url", url),
                Placeholder.unparsed("site", onModrinth ? "the Modrinth page" : "the GitHub release"),
                Placeholder.unparsed("button", onModrinth ? "[Download on Modrinth]" : "[Download on GitHub]")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission(PERMISSION)) {
            return;
        }
        LocalDate today = LocalDate.now();
        notified.values().removeIf(d -> d.isBefore(today));
        if (today.equals(notified.get(p.getUniqueId()))) {
            return;
        }
        Runnable send = () -> {
            if (!latest.isEmpty() && p.isOnline()) {
                notice(p);
                notified.put(p.getUniqueId(), today);
            }
        };
        if (!latest.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, send, 40L);
        } else if (System.currentTimeMillis() - lastCheck > 3_600_000L) {
            check().thenAccept(newer -> {
                if (newer) {
                    Bukkit.getScheduler().runTask(plugin, send);
                }
            });
        }
    }
}
