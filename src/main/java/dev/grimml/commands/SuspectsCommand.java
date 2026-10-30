package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.grim.PlayerEvidence;
import dev.grimml.grim.ScoreMath;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class SuspectsCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public SuspectsCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (plugin.evidence() == null) {
            GrimMLCommand.msg(sender, "<red>evidence off");
            return;
        }
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                GrimMLCommand.msg(sender, "<red>/grimml suspects [limit]");
                return;
            }
        }
        List<PlayerEvidence> top = plugin.evidence().top(limit);
        if (top.isEmpty()) {
            GrimMLCommand.msg(sender, "<gray>no grim scores");
            return;
        }
        double scale = plugin.grimWeights().confidenceScale;
        GrimMLCommand.msg(sender, "<gold>grim suspects (anon only) drops=" + plugin.evidence().drops());
        for (PlayerEvidence ev : top) {
            GrimMLCommand.msg(sender, "<white>" + Long.toUnsignedString(ev.anonId, 36)
                    + " <gray>score=" + String.format(java.util.Locale.ROOT, "%.2f", ev.score)
                    + " conf=" + String.format(java.util.Locale.ROOT, "%.2f", ScoreMath.confidence(ev.score, scale))
                    + (ev.promoted ? " <yellow>promoted" : ""));
        }
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return List.of("5", "10", "20");
        }
        return List.of();
    }
}
