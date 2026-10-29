package dev.grimml.commands;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface SubCommand {

    void execute(CommandSender sender, String[] args);

    default List<String> suggest(String[] args) {
        return List.of();
    }
}
