package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class TestAccountCommand implements SubCommand {

    private final GrimMLPlugin plugin;

    public TestAccountCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player target;
        boolean on;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) {
            target = Bukkit.getPlayerExact(args[1]);
            on = args.length < 3 || !args[2].equalsIgnoreCase("off");
        } else if (sender instanceof Player player) {
            target = player;
            on = args.length < 2 || !args[1].equalsIgnoreCase("off");
        } else {
            GrimMLCommand.msg(sender, "<red>/grimml testaccount <account> [on|off]");
            return;
        }
        if (target == null) {
            GrimMLCommand.msg(sender, "<red>account not online");
            return;
        }
        plugin.testAccounts().set(target.getUniqueId(), on);
        plugin.testAccounts().save();
        PlayerRingBuffer buf = plugin.registry().get(target.getUniqueId());
        if (buf != null) {
            buf.testAccount = on;
            plugin.writer().enqueueSessionLine("testaccount " + Long.toUnsignedString(buf.anonPlayerId, 36) + " " + on);
        }
        GrimMLCommand.msg(sender, "<green>test account " + target.getName() + " " + (on ? "on" : "off"));
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            List<String> out = new ArrayList<>();
            out.add("on");
            out.add("off");
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
            String pfx = args[1];
            return out.stream().filter(s -> s.regionMatches(true, 0, pfx, 0, pfx.length())).toList();
        }
        if (args.length == 3) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
