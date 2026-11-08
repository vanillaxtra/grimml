package dev.grimml.commands;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.RecordTypes;
import dev.grimml.mark.MarkFeedback;
import dev.grimml.mark.MarkLog;
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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MarkCommand implements SubCommand {

    private static final String USAGE = "<red>/grimml mark start <account> <label> [client] [module] [key=value...]\n"
            + "<red>/grimml mark end <account>\n"
            + "<red>/grimml mark list\n"
            + "<red>/grimml mark get <id>";

    private final GrimMLPlugin plugin;

    public MarkCommand(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            GrimMLCommand.msg(sender, USAGE);
            return;
        }
        String op = args[1];
        if (op.equalsIgnoreCase("list")) {
            if (args.length > 2) {
                show(sender, args[2]);
            } else {
                list(sender);
            }
            return;
        }
        if (op.equalsIgnoreCase("get")) {
            if (args.length < 3) {
                GrimMLCommand.msg(sender, "<red>/grimml mark get <id>");
                return;
            }
            show(sender, args[2]);
            return;
        }
        boolean start = op.equalsIgnoreCase("start");
        boolean end = op.equalsIgnoreCase("end");
        if (!start && !end) {
            GrimMLCommand.msg(sender, USAGE);
            return;
        }
        if (args.length < 3) {
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
            MarkTracker.Open prev = plugin.closeOpen(target.getUniqueId(), buf, false);
            if (prev == null) {
                GrimMLCommand.msg(sender, "<red>no open segment");
                return;
            }
            GrimMLCommand.msg(sender, "<green>marked end " + target.getName()
                    + " id=<yellow>" + prev.id + "</yellow> " + clock(System.nanoTime() - prev.startNanos));
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
        String id = MarkTracker.newId();
        MarkTracker.Open prev = plugin.marks().start(target.getUniqueId(), id, sender.getName(), target.getName(),
                System.nanoTime(), System.currentTimeMillis(), labelId, clientId, moduleId, settings, kv.toArray(String[]::new));
        if (prev != null) {
            plugin.writeClosedMark(prev, buf, true);
        }
        MarkerWriter.write(buf, plugin.clock(), plugin.writer(), post, RecordTypes.MARKER_START,
                labelId, clientId, moduleId, kv, false, id);
        buf.markActive = true;
        buf.markId = id;
        MarkTracker.Open cur = plugin.marks().peek(target.getUniqueId());
        plugin.logMark(cur, buf, "start", false);
        GrimMLCommand.msg(sender, "<green>marked start " + target.getName() + " " + Labels.name(labelId)
                + (module.isEmpty() ? "" : " " + module)
                + " id=<yellow>" + id + "</yellow>");
        GrimMLCommand.msg(sender, "<gray>files *_" + id + "_*.gmlml");
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return prefix(List.of("start", "end", "list", "get"), args[1]);
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("list"))) {
            List<String> ids = new ArrayList<>();
            for (MarkTracker.Open o : plugin.marks().allOpen()) {
                ids.add(o.id);
            }
            return prefix(ids, args[2]);
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
            if (key.equalsIgnoreCase("client-name")) {
                List<String> out = new ArrayList<>();
                for (String n : plugin.taxonomy().clientNames) {
                    if (n.regionMatches(true, 0, val, 0, val.length())) {
                        out.add("client-name=" + n);
                    }
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
        if (!alreadySet(args, "client-name")) {
            keys.add("client-name=");
        }
        if (spec != null) {
            for (String k : spec.settings.keySet()) {
                if (!alreadySet(args, k)) {
                    keys.add(k + "=");
                }
            }
        }
        return prefix(keys, cur);
    }

    private void list(CommandSender sender) {
        var open = plugin.marks().allOpen();
        if (open.isEmpty()) {
            GrimMLCommand.msg(sender, "<gray>no open marks");
        } else {
            GrimMLCommand.msg(sender, "<gold>open");
            for (MarkTracker.Open o : open) {
                GrimMLCommand.msg(sender, line(o, (int) ((System.nanoTime() - o.startNanos) / 1_000_000_000L), true));
            }
        }
        List<MarkLog.Row> recent;
        try {
            recent = plugin.markLog() == null ? List.of() : plugin.markLog().latestById(15);
        } catch (IOException e) {
            GrimMLCommand.msg(sender, "<red>marks.csv " + e.getMessage());
            return;
        }
        boolean any = false;
        for (MarkLog.Row r : recent) {
            if (plugin.marks().byId(r.id) != null) {
                continue;
            }
            if (!any) {
                GrimMLCommand.msg(sender, "<gold>recent");
                any = true;
            }
            GrimMLCommand.msg(sender, line(r));
        }
    }

    private void show(CommandSender sender, String id) {
        MarkTracker.Open open = plugin.marks().byId(id);
        List<MarkLog.Row> rows;
        try {
            rows = plugin.markLog() == null ? List.of() : plugin.markLog().byId(id);
        } catch (IOException e) {
            GrimMLCommand.msg(sender, "<red>marks.csv " + e.getMessage());
            return;
        }
        if (open == null && rows.isEmpty()) {
            GrimMLCommand.msg(sender, "<red>unknown id " + id);
            return;
        }
        if (open != null) {
            GrimMLCommand.msg(sender, line(open, (int) ((System.nanoTime() - open.startNanos) / 1_000_000_000L), true));
            if (open.kv.length > 0) {
                GrimMLCommand.msg(sender, "<gray>" + String.join(" ", open.kv));
            }
        }
        for (MarkLog.Row r : rows) {
            GrimMLCommand.msg(sender, line(r));
            if (!r.settings.isEmpty()) {
                GrimMLCommand.msg(sender, "<gray>" + r.settings.replace(';', ' '));
            }
        }
        int files = countFiles(open != null ? open.id : id);
        GrimMLCommand.msg(sender, "<gray>files (today and yesterday) " + files + " *_" + (open != null ? open.id : id) + "_*.gmlml");
    }

    private String line(MarkTracker.Open o, int elapsed, boolean live) {
        String client = MarkFeedback.clientNameOf(o.kv);
        if (client.isEmpty()) {
            client = plugin.taxonomy().clientName(o.clientId);
        }
        String module = plugin.taxonomy().moduleName(o.moduleId);
        return "<yellow>" + o.id + "</yellow> <white>" + Labels.name(o.labelId)
                + (module.isEmpty() ? "" : " " + module) + "</white>"
                + (client.isEmpty() ? "" : " <gray>" + client + "</gray>")
                + " <aqua>" + clock(elapsed * 1_000_000_000L) + "</aqua>"
                + " <gray>" + o.account + " by " + o.operator
                + (live ? " open" : "") + "</gray>";
    }

    private static String line(MarkLog.Row r) {
        return "<yellow>" + r.id + "</yellow> <white>" + r.label
                + (r.module.isEmpty() ? "" : " " + r.module) + "</white>"
                + (r.client.isEmpty() ? "" : " <gray>" + r.client + "</gray>")
                + " <aqua>" + clock(r.elapsedS * 1_000_000_000L) + "</aqua>"
                + " <gray>" + r.account + " by " + r.operator
                + " " + r.event + (r.interrupted ? " interrupted" : "") + "</gray>";
    }

    private int countFiles(String id) {
        if (plugin.writer() == null || id == null || id.isEmpty()) {
            return 0;
        }
        Path dir = plugin.writer().recordingsDir();
        java.time.LocalDate today = java.time.LocalDate.now();
        int n = countIn(dir, id);
        for (java.time.LocalDate day : List.of(today, today.minusDays(1))) {
            Path dayDir = dir.resolve(day.toString());
            if (!Files.isDirectory(dayDir)) {
                continue;
            }
            n += countIn(dayDir, id);
            try (DirectoryStream<Path> hours = Files.newDirectoryStream(dayDir, Files::isDirectory)) {
                for (Path hour : hours) {
                    n += countIn(hour, id);
                }
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    // root plus today and yesterday only so the command never walks the whole dump on the main thread
    private static int countIn(Path dir, String id) {
        int n = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + id + "*")) {
            for (Path ignored : stream) {
                n++;
            }
        } catch (IOException e) {
            return 0;
        }
        return n;
    }

    static String clock(long nanos) {
        long s = Math.max(0L, nanos / 1_000_000_000L);
        return (s / 60) + String.format(":%02d", s % 60);
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
