package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

public final class PauseCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public PauseCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender) {
        plugin.writer().setPaused(true);
        GrimMLCommand.msg(plugin, sender, "<yellow>writer paused");
    }
}
