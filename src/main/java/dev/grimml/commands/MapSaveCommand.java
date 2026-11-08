package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MapSaveCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public MapSaveCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            GrimMLCommand.msg(sender, "<red>run in game at the arena center");
            return;
        }
        if (plugin.maps() == null) {
            GrimMLCommand.msg(sender, "<red>map snapshots unavailable");
            return;
        }
        String key = plugin.maps().capture(player.getLocation());
        if (key == null) {
            GrimMLCommand.msg(sender, "<yellow>a snapshot for this map is already running");
            return;
        }
        GrimMLCommand.msg(sender, "<green>map snapshot " + key + " started <gray>(radius "
                + plugin.config().mapSnapshotRadius() + ", see console when saved)");
    }
}
