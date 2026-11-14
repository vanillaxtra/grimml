package dev.grimml.mark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

// staff ledger of marked sessions; not a training sidecar
public final class MarkLog {

    static final String HEADER = "id,event,epoch_ms,elapsed_s,operator,account,anon,label,client,module,interrupted,settings\n";

    public static final class Row {
        public final String id;
        public final String event;
        public final long epochMs;
        public final int elapsedS;
        public final String operator;
        public final String account;
        public final String anon;
        public final String label;
        public final String client;
        public final String module;
        public final boolean interrupted;
        public final String settings;

        public Row(String id, String event, long epochMs, int elapsedS, String operator, String account,
                   String anon, String label, String client, String module, boolean interrupted, String settings) {
            this.id = id;
            this.event = event;
            this.epochMs = epochMs;
            this.elapsedS = elapsedS;
            this.operator = operator;
            this.account = account;
            this.anon = anon;
            this.label = label;
            this.client = client;
            this.module = module;
            this.interrupted = interrupted;
            this.settings = settings;
        }
    }

    private final Path file;
    private final Object lock = new Object();

    public MarkLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void append(String id, String event, long epochMs, int elapsedS, String operator, String account,
                       String anon, String label, String client, String module, boolean interrupted, String[] kv)
            throws IOException {
        String settings = joinKv(kv);
        String line = csv(id) + "," + csv(event) + "," + epochMs + "," + elapsedS + ","
                + csv(operator) + "," + csv(account) + "," + csv(anon) + "," + csv(label) + ","
                + csv(client) + "," + csv(module) + "," + interrupted + "," + csv(settings) + "\n";
        synchronized (lock) {
            if (!Files.exists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    public List<Row> byId(String id) throws IOException {
        List<Row> out = new ArrayList<>();
        if (id == null || id.isEmpty()) {
            return out;
        }
        for (Row r : readAll()) {
            if (r.id.equalsIgnoreCase(id)) {
                out.add(r);
            }
        }
        return out;
    }

    public List<Row> latestById(int limit) throws IOException {
        List<Row> all = readAll();
        LinkedHashMap<String, Row> last = new LinkedHashMap<>();
        for (int i = all.size() - 1; i >= 0 && last.size() < limit; i--) {
            Row r = all.get(i);
            last.putIfAbsent(r.id.toLowerCase(), r);
        }
        return new ArrayList<>(last.values());
    }

    List<Row> readAll() throws IOException {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Row> out = new ArrayList<>();
            for (String line : lines) {
                Row r = parse(line);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    static Row parse(String line) {
        if (line == null || line.isEmpty() || line.startsWith("id,")) {
            return null;
        }
        String[] p = splitCsv(line, 12);
        if (p == null || p[0].isEmpty()) {
            return null;
        }
        try {
            return new Row(p[0], p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]),
                    p[4], p[5], p[6], p[7], p[8], p[9], Boolean.parseBoolean(p[10]), p[11]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String joinKv(String[] kv) {
        if (kv == null || kv.length == 0) {
            return "";
        }
        return String.join(";", kv);
    }

    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').replace(',', ' ').replace('"', '\'').trim();
        if (t.length() > 400) {
            return t.substring(0, 400);
        }
        return t;
    }

    // last field keeps remainder so settings can contain leftover separators
    static String[] splitCsv(String line, int n) {
        String[] out = new String[n];
        int start = 0;
        for (int i = 0; i < n - 1; i++) {
            int c = line.indexOf(',', start);
            if (c < 0) {
                return null;
            }
            out[i] = line.substring(start, c);
            start = c + 1;
        }
        out[n - 1] = start <= line.length() ? line.substring(start) : "";
        return out;
    }
}
