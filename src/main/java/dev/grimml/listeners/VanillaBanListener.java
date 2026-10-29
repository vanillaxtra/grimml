package dev.grimml.listeners;

import dev.grimml.roster.BanLabelService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

// vanilla /ban kick fallback when litebans is not the source
public final class VanillaBanListener implements Listener {

    private final BanLabelService labels;

    public VanillaBanListener(BanLabelService labels) {
        this.labels = labels;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (!isBan(event)) {
            return;
        }
        String reason = event.reason() == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.reason());
        labels.onCombatBan(event.getPlayer().getUniqueId(), reason, "vanilla");
    }

    private static boolean isBan(PlayerKickEvent event) {
        try {
            String cause = event.getCause().name();
            return cause.contains("BAN");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
