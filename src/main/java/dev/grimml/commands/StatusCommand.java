package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.writer.RecordWriter;
import org.bukkit.command.CommandSender;

public final class StatusCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public StatusCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        RecordWriter w = plugin.writer();
        GrimMLCommand.msg(sender, "<green>enabled: " + plugin.recordingEnabled()
                + " paused: " + w.isPaused()
                + " written windows: " + w.writtenWindows()
                + " dropped windows: " + w.droppedWindows()
                + " written bytes: " + w.writtenBytes()
                + " " + w.drops().summary());
        GrimMLCommand.msg(sender, "<gray>dropped records by type: " + w.drops().byTypeSummary());
        var inference = plugin.inference();
        GrimMLCommand.msg(sender, "<gray>inference: " + (inference == null ? "disabled" : inference.stats())
                + String.format(java.util.Locale.ROOT, " mspt %.1f", plugin.clock().mspt()));
    }
}
