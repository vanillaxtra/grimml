package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.record.RecordWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class GrimMLCommand implements CommandExecutor, TabCompleter {

    private final GrimMLPlugin plugin;

    public GrimMLCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            msg(sender, "<red>/grimml <status|pause|resume|reload>");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                RecordWriter w = plugin.writer();
                msg(sender, "<green>enabled: " + plugin.recordingEnabled()
                        + " paused: " + w.isPaused()
                        + " written windows: " + w.writtenWindows()
                        + " dropped windows: " + w.droppedWindows()
                        + " written bytes: " + w.writtenBytes());
            }
            case "pause" -> {
                plugin.writer().setPaused(true);
                msg(sender, "<yellow>writer paused");
            }
            case "resume" -> {
                plugin.writer().setPaused(false);
                msg(sender, "<green>writer resumed");
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.config().load(plugin.getConfig());
                msg(sender, "<green>config reloaded");
            }
            default -> msg(sender, "<red>/grimml <status|pause|resume|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("status", "pause", "resume", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private void msg(CommandSender sender, String mini) {
        Component c = MiniMessage.miniMessage().deserialize(mini);
        if (sender instanceof org.bukkit.entity.Player p) {
            p.sendMessage(c);
        } else {
            plugin.getComponentLogger().info(c);
        }
    }
}
