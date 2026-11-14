package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

public final class PauseCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public PauseCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.writer().setPaused(true);
        GrimMLCommand.msg(sender, "<yellow>writer paused");
    }
}
