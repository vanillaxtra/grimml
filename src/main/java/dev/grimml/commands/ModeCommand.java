package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

import java.util.List;

// runtime only; config.yml recording-mode wins again on reload or restart
public final class ModeCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public ModeCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("markonly")) {
            GrimMLCommand.msg(sender, "<gray>recording-mode: <white>" + (plugin.config().markOnly() ? "mark-only" : "auto")
                    + "</white> <red>/grimml mode markonly on|off");
            return;
        }
        boolean on;
        if (args[2].equalsIgnoreCase("on")) {
            on = true;
        } else if (args[2].equalsIgnoreCase("off")) {
            on = false;
        } else {
            GrimMLCommand.msg(sender, "<red>/grimml mode markonly on|off");
            return;
        }
        plugin.config().setMarkOnly(on);
        if (plugin.registry() != null) {
            plugin.registry().propagateMarkOnly(on);
        }
        GrimMLCommand.msg(sender, "<green>recording-mode " + (on ? "mark-only" : "auto")
                + " <gray>until reload or restart (config.yml unchanged)");
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return List.of("markonly");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("markonly")) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
