package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.RecordTypes;
import dev.grimml.mark.MarkTracker;
import dev.grimml.mark.MarkerWriter;
import dev.grimml.taxonomy.Labels;
import dev.grimml.taxonomy.ModuleSpec;
import dev.grimml.taxonomy.SettingSpec;
import dev.grimml.taxonomy.Taxonomy;
import dev.grimml.taxonomy.TaxonomyLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MarkCommand implements SubCommand {

    private static final String USAGE = "<red>/grimml mark start <account> <label> [client] [module] [key=value...]\n<red>/grimml mark end <account>";

    private final GrimMLPlugin plugin;

    public MarkCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            GrimMLCommand.msg(sender, USAGE);
            return;
        }
        boolean start = args[1].equalsIgnoreCase("start");
        boolean end = args[1].equalsIgnoreCase("end");
        if (!start && !end) {
            GrimMLCommand.msg(sender, USAGE);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            GrimMLCommand.msg(sender, "<red>account not online");
            return;
        }
        PlayerRingBuffer buf = plugin.registry().get(target.getUniqueId());
        if (buf == null) {
            GrimMLCommand.msg(sender, "<red>no session");
            return;
        }
        long post = plugin.config().postCombatSeconds() * 1_000_000_000L;
        if (end) {
            MarkTracker.Open prev = plugin.marks().end(target.getUniqueId());
            if (prev == null) {
                GrimMLCommand.msg(sender, "<red>no open segment");
                return;
            }
            MarkerWriter.write(buf, plugin.clock(), plugin.writer(), post, RecordTypes.MARKER_END,
                    prev.labelId, prev.clientId, prev.moduleId, List.of(prev.kv), false);
            GrimMLCommand.msg(sender, "<green>marked end " + target.getName());
            return;
        }
        if (args.length < 4) {
            GrimMLCommand.msg(sender, USAGE);
            return;
        }
        int labelId = Labels.id(args[3]);
        if (labelId == Labels.ID_UNSET) {
            GrimMLCommand.msg(sender, "<red>label LEGIT_CONFIRMED or CHEAT");
            return;
        }
        Taxonomy tax = plugin.taxonomy();
        int i = 4;
        String client = "";
        String module = "";
        if (i < args.length && args[i].indexOf('=') < 0 && tax.hasClient(args[i])) {
            client = tax.findClient(args[i]);
            i++;
        }
        if (i < args.length && args[i].indexOf('=') < 0) {
            ModuleSpec spec = tax.module(args[i]);
            if (spec == null) {
                GrimMLCommand.msg(sender, "<red>unknown module " + args[i]);
                return;
            }
            module = spec.name;
            i++;
        }
        List<String> kv = TaxonomyLoader.kvPairs(args, i);
        ModuleSpec spec = module.isEmpty() ? null : tax.module(module);
        String err = TaxonomyLoader.validateKv(spec, kv);
        if (spec != null && err != null) {
            GrimMLCommand.msg(sender, "<red>" + err);
            return;
        }
        if (spec == null && !kv.isEmpty()) {
            String extra = TaxonomyLoader.validateFree(kv);
            if (extra != null) {
                GrimMLCommand.msg(sender, "<red>" + extra);
                return;
            }
        }
        int clientId = tax.clientId(client);
        int moduleId = tax.moduleId(module);
        int settings = MarkerWriter.hashKv(kv);
        MarkTracker.Open prev = plugin.marks().start(target.getUniqueId(), System.nanoTime(),
                System.currentTimeMillis(), labelId, clientId, moduleId, settings, kv.toArray(String[]::new));
        if (prev != null) {
            MarkerWriter.write(buf, plugin.clock(), plugin.writer(), post, RecordTypes.MARKER_END,
                    prev.labelId, prev.clientId, prev.moduleId, List.of(prev.kv), true);
        }
        MarkerWriter.write(buf, plugin.clock(), plugin.writer(), post, RecordTypes.MARKER_START,
                labelId, clientId, moduleId, kv, false);
        GrimMLCommand.msg(sender, "<green>marked start " + target.getName() + " " + Labels.name(labelId)
                + (module.isEmpty() ? "" : " " + module));
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return prefix(List.of("start", "end"), args[1]);
        }
        if (args.length == 3) {
            return prefix(testAccountNames(), args[2]);
        }
        if (!args[1].equalsIgnoreCase("start")) {
            return List.of();
        }
        if (args.length == 4) {
            return prefix(Labels.ALL, args[3]);
        }
        Taxonomy tax = plugin.taxonomy();
        boolean clientDone = args.length > 4 && tax.hasClient(args[4]);
        if (args.length == 5 && !args[4].contains("=")) {
            List<String> both = new ArrayList<>(tax.clients);
            both.addAll(tax.moduleNames());
            return prefix(both, args[4]);
        }
        int moduleIdx = clientDone ? 5 : 4;
        if (args.length == moduleIdx + 1 && !args[moduleIdx].contains("=")) {
            return prefix(tax.moduleNames(), args[moduleIdx]);
        }
        ModuleSpec spec = null;
        if (args.length > moduleIdx) {
            spec = tax.module(args[moduleIdx]);
        }
        String cur = args[args.length - 1];
        int eq = cur.indexOf('=');
        if (eq >= 0) {
            String key = cur.substring(0, eq);
            String val = cur.substring(eq + 1);
            if (key.equalsIgnoreCase("expected") && plugin.grimChecks() != null) {
                List<String> names = plugin.grimChecks().complete(val);
                List<String> out = new ArrayList<>();
                for (String n : names) {
                    out.add(key + "=" + n);
                }
                return out;
            }
            if (spec != null) {
                SettingSpec s = spec.settings.get(key);
                if (s == null) {
                    for (var e : spec.settings.entrySet()) {
                        if (e.getKey().equalsIgnoreCase(key)) {
                            s = e.getValue();
                            key = e.getKey();
                            break;
                        }
                    }
                }
                if (s != null && s.kind == SettingSpec.Kind.ENUM) {
                    List<String> out = new ArrayList<>();
                    for (String v : s.enums) {
                        if (v.regionMatches(true, 0, val, 0, val.length())) {
                            out.add(key + "=" + v);
                        }
                    }
                    return out;
                }
            }
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        keys.add("expected=");
        if (spec != null) {
            for (String k : spec.settings.keySet()) {
                if (!alreadySet(args, k)) {
                    keys.add(k + "=");
                }
            }
        }
        return prefix(keys, cur);
    }

    private List<String> testAccountNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.testAccounts() != null && plugin.testAccounts().isTest(p.getUniqueId())) {
                out.add(p.getName());
            }
        }
        if (out.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        }
        return out;
    }

    private static boolean alreadySet(String[] args, String key) {
        String p = key + "=";
        for (String a : args) {
            if (a.regionMatches(true, 0, p, 0, p.length())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> prefix(List<String> all, String p) {
        String x = p == null ? "" : p;
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (s.regionMatches(true, 0, x, 0, x.length())) {
                out.add(s);
            }
        }
        return out;
    }

}
