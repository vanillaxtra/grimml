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
    public void execute(CommandSender sender) {
        RecordWriter w = plugin.writer();
        GrimMLCommand.msg(plugin, sender, "<green>enabled: " + plugin.recordingEnabled()
                + " paused: " + w.isPaused()
                + " written windows: " + w.writtenWindows()
                + " dropped windows: " + w.droppedWindows()
                + " written bytes: " + w.writtenBytes());
    }
}
