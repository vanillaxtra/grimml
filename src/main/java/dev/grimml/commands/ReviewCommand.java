package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ReviewCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public ReviewCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (plugin.evidenceStore() == null) {
            GrimMLCommand.msg(sender, "<red>evidence off");
            return;
        }
        if (args.length < 2) {
            GrimMLCommand.msg(sender, "<red>/grimml review next|mark <id> confirmed|legit|unsure");
            return;
        }
        if (args[1].equalsIgnoreCase("next")) {
            String line = plugin.evidenceStore().nextPending();
            if (line == null) {
                GrimMLCommand.msg(sender, "<gray>review queue empty");
                return;
            }
            GrimMLCommand.msg(sender, "<gold>review " + line);
            return;
        }
        if (args[1].equalsIgnoreCase("mark")) {
            if (args.length < 4) {
                GrimMLCommand.msg(sender, "<red>/grimml review mark <id> confirmed|legit|unsure");
                return;
            }
            long id;
            try {
                id = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                GrimMLCommand.msg(sender, "<red>bad review id");
                return;
            }
            String status = args[3].toLowerCase();
            if (!plugin.evidenceStore().markReview(id, status)) {
                GrimMLCommand.msg(sender, "<red>unknown review or status");
                return;
            }
            GrimMLCommand.msg(sender, "<green>review " + id + " " + status);
            return;
        }
        GrimMLCommand.msg(sender, "<red>/grimml review next|mark <id> confirmed|legit|unsure");
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return List.of("next", "mark");
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("mark")) {
            return List.of("confirmed", "legit", "unsure");
        }
        return List.of();
    }
}
