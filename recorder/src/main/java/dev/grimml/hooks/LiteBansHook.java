package dev.grimml.hooks;

import dev.grimml.GrimMLPlugin;
import dev.grimml.roster.BanLabelService;
import litebans.api.Entry;
import litebans.api.Events;
import org.bukkit.Bukkit;

import java.util.UUID;

// litebans entryAdded is async file append only
public final class LiteBansHook {

    private final GrimMLPlugin plugin;
    private final BanLabelService labels;

    public LiteBansHook(GrimMLPlugin plugin, BanLabelService labels) {
        this.plugin = plugin;
        this.labels = labels;
    }

    public void enable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LiteBans")) {
            return;
        }
        try {
            Events.get().register(new Events.Listener() {
                @Override
                public void entryAdded(Entry entry) {
                    if (entry == null || !"ban".equalsIgnoreCase(entry.getType())) {
                        return;
                    }
                    UUID uuid = BanLabelService.parseUuid(entry.getUuid());
                    if (uuid == null) {
                        return;
                    }
                    labels.onCombatBan(uuid, entry.getReason(), "litebans");
                }
            });
            plugin.getLogger().info("litebans ban labels enabled");
        } catch (NoClassDefFoundError | IllegalStateException e) {
            plugin.getLogger().info("litebans api missing; combat ban labels skipped");
        }
    }
}
