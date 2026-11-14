package dev.grimml.mark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TestAccounts {

    private final Path file;
    private final ConcurrentHashMap<UUID, Boolean> ids = new ConcurrentHashMap<>();

    public TestAccounts(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                try {
                    ids.put(UUID.fromString(s), Boolean.TRUE);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isTest(UUID uuid) {
        return uuid != null && ids.containsKey(uuid);
    }

    public boolean set(UUID uuid, boolean on) {
        if (uuid == null) {
            return false;
        }
        if (on) {
            return ids.put(uuid, Boolean.TRUE) == null;
        }
        return ids.remove(uuid) != null;
    }

    public void save() {
        List<String> lines = new ArrayList<>();
        for (UUID u : ids.keySet()) {
            lines.add(u.toString());
        }
        lines.sort(String::compareTo);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
