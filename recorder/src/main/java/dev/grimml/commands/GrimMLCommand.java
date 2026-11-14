package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GrimMLCommand implements BasicCommand {

    public static final String PERMISSION = "grimml.admin";
    private static final String USAGE = "<red>/grimml <status|pause|resume|reload|mark|testaccount|suspects|review>";

    private final Map<String, SubCommand> subs = new LinkedHashMap<>();

    public GrimMLCommand(GrimMLPlugin plugin) {
        subs.put("status", new StatusCommand(plugin));
        subs.put("pause", new PauseCommand(plugin));
        subs.put("resume", new ResumeCommand(plugin));
        subs.put("reload", new ReloadCommand(plugin));
        subs.put("mark", new MarkCommand(plugin));
        subs.put("testaccount", new TestAccountCommand(plugin));
        subs.put("suspects", new SuspectsCommand(plugin));
        subs.put("review", new ReviewCommand(plugin));
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        SubCommand sub = args.length == 0 ? null : subs.get(args[0].toLowerCase());
        if (sub == null) {
            msg(sender, USAGE);
            return;
        }
        sub.execute(sender, args);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length == 0) {
            return subs.keySet();
        }
        if (args.length == 1) {
            return subs.keySet().stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        SubCommand sub = subs.get(args[0].toLowerCase());
        if (sub == null) {
            return List.of();
        }
        return sub.suggest(args);
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    static void msg(CommandSender sender, String mini) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(mini));
    }
}
