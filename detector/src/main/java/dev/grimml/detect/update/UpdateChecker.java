package dev.grimml.detect.update;

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
 * Checks GitHub's latest release off the main thread. If it is newer than this jar: a console banner on start, and a
 * clickable notice for players with grimml.update.notify, once per player per day, 2 s after they join.
 * Based on the PluginBrain update checker template (GitHub only: GrimML is not on Modrinth).
 */
public final class UpdateChecker implements Listener {
    private static final String REPO = "vanillaxtra/grimml";
    private static final String PERMISSION = "grimml.update.notify";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Version current;
    private final String currentLabel;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String latest = "";
    private volatile String url = "";
    private volatile long lastCheck; // GitHub allows 60 unauthenticated calls an hour, so joins re-check hourly at most
    private final Map<UUID, LocalDate> notified = new HashMap<>(); // main thread only

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.currentLabel = plugin.getPluginMeta().getVersion();
        this.current = new Version(currentLabel);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        check().thenAccept(newer -> {
            if (newer) {
                banner();
            }
        });
    }

    /** Async; true when the latest GitHub release is newer than this jar. Never throws. */
    public CompletableFuture<Boolean> check() {
        lastCheck = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GrimML-UpdateChecker/" + currentLabel)
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 404) {
                return false; // no release published yet
            }
            if (r.statusCode() != 200) {
                plugin.getLogger().fine("update check: HTTP " + r.statusCode());
                return false;
            }
            JsonObject rel = JsonParser.parseString(r.body()).getAsJsonObject();
            if (!rel.has("tag_name")) {
                return false;
            }
            String tag = rel.get("tag_name").getAsString().replaceAll("^[vV]", "");
            boolean newer = new Version(tag).compareTo(current) > 0;
            if (newer) {
                latest = tag;
                url = rel.has("html_url") ? rel.get("html_url").getAsString()
                        : "https://github.com/" + REPO + "/releases/latest";
            }
            return newer;
        }).exceptionally(ex -> {
            plugin.getLogger().fine("update check failed: " + ex.getMessage());
            return false;
        });
    }

    private void banner() {
        var log = plugin.getLogger();
        log.info("----------------------------------------------------");
        log.info("  A GrimML update is available: " + currentLabel + " -> " + latest);
        log.info("  " + url);
        log.info("----------------------------------------------------");
    }

    private void notice(Player p) {
        if (latest.isEmpty() || !p.isOnline() || !p.hasPermission(PERMISSION)) {
            return;
        }
        p.sendMessage(MM.deserialize(
                "<aqua>GrimML <dark_gray>» <gray>Update available: <red><current></red> <dark_gray>→ <green><latest></green> "
                        + "<gold><click:open_url:'<url>'><hover:show_text:'<gray>Open the GitHub release'>[Download]</hover></click>",
                Placeholder.unparsed("current", currentLabel), Placeholder.unparsed("latest", latest),
                Placeholder.unparsed("url", url)));
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
