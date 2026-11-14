package dev.grimml.geyser;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

// detects bedrock/floodgate players without hard dependency
public final class GeyserHook {

    private boolean geyserPresent;
    private boolean floodgatePresent;

    public void init() {
        geyserPresent = Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
        floodgatePresent = Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    public boolean isBedrock(Player player) {
        if (player == null) {
            return false;
        }
        if (floodgatePresent) {
            try {
                Object api = Class.forName("org.geysermc.floodgate.api.FloodgateApi").getMethod("getInstance").invoke(null);
                return (boolean) api.getClass().getMethod("isFloodgatePlayer", java.util.UUID.class)
                        .invoke(api, player.getUniqueId());
            } catch (Throwable ignored) {
            }
        }
        if (geyserPresent) {
            try {
                Object api = Class.forName("org.geysermc.api.GeyserApiBase").getMethod("api").invoke(null);
                return (boolean) api.getClass().getMethod("isBedrockPlayer", java.util.UUID.class)
                        .invoke(api, player.getUniqueId());
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
