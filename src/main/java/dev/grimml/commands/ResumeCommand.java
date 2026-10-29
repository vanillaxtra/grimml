package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

public final class ResumeCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public ResumeCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender) {
        plugin.writer().setPaused(false);
        GrimMLCommand.msg(sender, "<green>writer resumed");
    }
}
