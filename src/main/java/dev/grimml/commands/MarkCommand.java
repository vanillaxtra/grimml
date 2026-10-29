package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.BinaryFormat;
import dev.grimml.format.RecordTypes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class MarkCommand implements SubCommand {

    private final GrimMLPlugin plugin;
    private final TickClock clock;

    public MarkCommand(GrimMLPlugin plugin, TickClock clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            GrimMLCommand.msg(sender, "<red>/grimml mark <start|end> <label> [client] [module] [settings]");
            return;
        }
        if (!(sender instanceof Player player)) {
            GrimMLCommand.msg(sender, "<red>player only");
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
        if (buf == null) {
            GrimMLCommand.msg(sender, "<red>no session");
            return;
        }
        boolean start = args[1].equalsIgnoreCase("start");
        int label = BinaryFormat.hashCheckName(args[2]);
        int client = args.length > 3 ? BinaryFormat.hashCheckName(args[3]) : 0;
        int module = args.length > 4 ? BinaryFormat.hashCheckName(args[4]) : 0;
        int settings = args.length > 5 ? BinaryFormat.hashCheckName(args[5]) : 0;
        long nanos = System.nanoTime();
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.MARKER, tick, clock.nsOffset(nanos), ord, nanos,
                label, start ? 1 : 0, client, module, settings, 0);
        buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_MARKER);
        plugin.writer().enqueueSessionLine("mark " + Long.toUnsignedString(buf.anonPlayerId, 36)
                + " " + args[1] + " " + args[2]
                + (args.length > 3 ? " " + args[3] : "")
                + (args.length > 4 ? " " + args[4] : "")
                + (args.length > 5 ? " " + args[5] : ""));
        GrimMLCommand.msg(sender, "<green>marked " + args[1] + " " + args[2]);
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return List.of("start", "end");
        }
        if (args.length == 3) {
            return List.of("LEGIT_CONFIRMED", "CHEAT", "auto-swap", "auto-retotem", "jump-reset",
                    "inventory-macro", "auto-totem", "auto-shield", "auto-stun", "stun-slam",
                    "mace-macro", "wind-charge", "auto-web", "scaffold");
        }
        return List.of();
    }
}
