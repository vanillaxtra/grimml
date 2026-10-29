package dev.grimml.grim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// learned grim check names, file io never on netty
public final class GrimCheckCatalog {

    private final Path file;
    private final ConcurrentHashMap<String, Boolean> names = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public GrimCheckCatalog(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            parseJsonStrings(raw);
        } catch (IOException ignored) {
        }
    }

    public boolean learn(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (names.putIfAbsent(name, Boolean.TRUE) == null) {
            dirty.set(true);
            return true;
        }
        return false;
    }

    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<String> list = names();
        StringBuilder sb = new StringBuilder(64 + list.size() * 16);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(list.get(i))).append('"');
        }
        sb.append(']');
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            dirty.set(true);
        }
    }

    public List<String> names() {
        List<String> list = new ArrayList<>(names.keySet());
        Collections.sort(list);
        return list;
    }

    public List<String> complete(String prefix) {
        String p = prefix == null ? "" : prefix;
        List<String> out = new ArrayList<>();
        for (String n : names()) {
            if (n.regionMatches(true, 0, p, 0, p.length())) {
                out.add(n);
            }
        }
        return out;
    }

    void parseJsonStrings(String raw) {
        int i = 0;
        while (i < raw.length()) {
            int q = raw.indexOf('"', i);
            if (q < 0) {
                return;
            }
            StringBuilder n = new StringBuilder();
            i = q + 1;
            while (i < raw.length()) {
                char c = raw.charAt(i++);
                if (c == '\\' && i < raw.length()) {
                    n.append(raw.charAt(i++));
                } else if (c == '"') {
                    break;
                } else {
                    n.append(c);
                }
            }
            if (n.length() > 0) {
                names.put(n.toString(), Boolean.TRUE);
            }
        }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
