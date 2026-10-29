package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// routes /grimml to one class per subcommand
public final class GrimMLCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "<red>/grimml <status|pause|resume|reload>";

    private final GrimMLPlugin plugin;
    private final Map<String, SubCommand> subs = new LinkedHashMap<>();

    public GrimMLCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
        subs.put("status", new StatusCommand(plugin));
        subs.put("pause", new PauseCommand(plugin));
        subs.put("resume", new ResumeCommand(plugin));
        subs.put("reload", new ReloadCommand(plugin));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        SubCommand sub = args.length == 0 ? null : subs.get(args[0].toLowerCase());
        if (sub == null) {
            msg(plugin, sender, USAGE);
            return true;
        }
        sub.execute(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return subs.keySet().stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    static void msg(GrimMLPlugin plugin, CommandSender sender, String mini) {
        Component c = MiniMessage.miniMessage().deserialize(mini);
        if (sender instanceof org.bukkit.entity.Player p) {
            p.sendMessage(c);
        } else {
            plugin.getComponentLogger().info(c);
        }
    }
}
