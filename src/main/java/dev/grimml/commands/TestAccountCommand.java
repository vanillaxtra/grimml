package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class TestAccountCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public TestAccountCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            GrimMLCommand.msg(sender, "<red>player only");
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
        if (buf == null) {
            GrimMLCommand.msg(sender, "<red>no session");
            return;
        }
        boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
        buf.testAccount = on;
        plugin.writer().enqueueSessionLine("testaccount " + Long.toUnsignedString(buf.anonPlayerId, 36) + " " + on);
        GrimMLCommand.msg(sender, "<green>test account " + (on ? "on" : "off"));
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
