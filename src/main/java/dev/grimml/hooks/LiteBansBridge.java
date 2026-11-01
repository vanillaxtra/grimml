package dev.grimml.hooks;

import dev.grimml.GrimMLPlugin;
import dev.grimml.roster.BanLabelService;
import org.bukkit.Bukkit;

// never import litebans types so grimml loads without that plugin
public final class LiteBansBridge {

    private LiteBansBridge() {}

    public static void enableIfPresent(GrimMLPlugin plugin, BanLabelService labels) {
        if (Bukkit.getPluginManager().getPlugin("LiteBans") == null) {
            return;
        }
        try {
            Class.forName("litebans.api.Events", false, plugin.getClass().getClassLoader());
            new LiteBansHook(plugin, labels).enable();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            plugin.getLogger().info("litebans api not visible; combat ban labels skipped");
        } catch (Exception e) {
            plugin.getLogger().info("litebans hook skipped: " + e.getMessage());
        }
    }
}
