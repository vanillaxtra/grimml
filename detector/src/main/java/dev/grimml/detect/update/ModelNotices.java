package dev.grimml.detect.update;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * This is the model notices class that tells staff when the models were updated.
 * Every player with grimml.notify is told once: straight away if they are online, or the next time they join (even after a restart).
 *
 * Who was told about which version is saved in plugins/GrimML/model-notices.yml.
 * A fresh install says nothing as nothing was updated.
 * Everything runs on the main thread except writing the file.
 */
public final class ModelNotices implements Listener {
    private static final String PERMISSION = "grimml.notify";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Path file;
    private final Supplier<Component> prefix;
    private int updatedTo; // newest model version an update installed (0 = none yet)
    private int updatedFrom;
    private final Map<UUID, Integer> told = new HashMap<>();
    private final Object writeLock = new Object();
    private long saves;   // main thread
    private long written; // the newest save on disk, so an older save finishing late is skipped

    public ModelNotices(JavaPlugin plugin, Supplier<Component> prefix) {
        this.plugin = plugin;
        this.prefix = prefix;
        this.file = plugin.getDataFolder().toPath().resolve("model-notices.yml");
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // a model update was installed: tells staff online now and the rest when they join (main thread)
    public void updated(int from, int to) {
        if (to <= updatedTo) {
            return;
        }
        updatedFrom = from;
        updatedTo = to;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) {
                tell(p, true);
            }
        }
        save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (updatedTo == 0 || !p.hasPermission(PERMISSION) || told.getOrDefault(p.getUniqueId(), 0) >= updatedTo) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && told.getOrDefault(p.getUniqueId(), 0) < updatedTo) {
                tell(p, false);
                save();
            }
        }, 60L); // after the join messages and the update checker notice
    }

    // sends the message and sound, live = they were online when it updated
    private void tell(Player p, boolean live) {
        String text = live
                ? "<gray>Models updated <dark_gray>v<from> →</dark_gray> <white>v<to></white>. Now detecting with the new models."
                : "<gray>While you were away, models were updated <dark_gray>v<from> →</dark_gray> <white>v<to></white>. "
                        + "Now detecting with the new models.";
        p.sendMessage(prefix.get().append(Component.space()).append(MM.deserialize(text,
                Placeholder.unparsed("from", Integer.toString(updatedFrom)),
                Placeholder.unparsed("to", Integer.toString(updatedTo)))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
        told.put(p.getUniqueId(), updatedTo);
    }

    // loads model-notices.yml, starts fresh if it is missing or broken
    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("model-notices.yml is unreadable, starting fresh: " + e.getMessage());
            return;
        }
        updatedTo = y.getInt("updated-to", 0);
        updatedFrom = y.getInt("updated-from", 0);
        var sec = y.getConfigurationSection("told");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    told.put(UUID.fromString(k), sec.getInt(k));
                } catch (IllegalArgumentException ignored) {
                    // not a uuid: skip
                }
            }
        }
    }

    // builds the file on the main thread and writes it async (tmp file then move, so it is never half written)
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of(
                "Which model update each staff member was told about (GrimML writes this file, no need to edit it)."));
        y.set("updated-to", updatedTo);
        y.set("updated-from", updatedFrom);
        told.forEach((id, v) -> y.set("told." + id, v));
        String data = y.saveToString();
        long n = ++saves;
        Runnable write = () -> {
            synchronized (writeLock) {
                if (n < written) {
                    return;
                }
                written = n;
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().warning("could not save model-notices.yml: " + e.getMessage());
            }
            }
        };
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }
}
