package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

public final class ReloadCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public ReloadCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.reloadConfig();
        plugin.config().load(plugin.getConfig());
        plugin.reloadTaxonomy();
        plugin.reloadWeights();
        if (plugin.registry() != null) {
            plugin.registry().propagateMarkOnly(plugin.config().markOnly());
        }
        plugin.reloadInference();
        GrimMLCommand.msg(sender, "<green>config taxonomy grim-weights and model reloaded");
    }
}
