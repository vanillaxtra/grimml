package dev.grimml.mark;

import dev.grimml.writer.RecordingFiles;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MarkLogSelfCheck {

    public static void main(String[] args) throws Exception {
        String id = MarkTracker.newId();
        if (id.length() != 9 || id.charAt(0) != 'm') {
            throw new AssertionError("id format " + id);
        }
        for (int i = 1; i < 9; i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new AssertionError("id hex " + id);
            }
        }
        String marked = RecordingFiles.fileName("2026-11-02", "m83e5404d", 1L, 2L, "a3ac2e82");
        if (!marked.equals("2026-11-02_m83e5404d_a3ac2e82.gmlml")) {
            throw new AssertionError("marked name " + marked);
        }
        String auto = RecordingFiles.fileName("2026-11-02", "", 1L, 2L, "a3ac2e82");
        if (!auto.contains("a3ac2e82") || auto.contains("m83e5404d")) {
            throw new AssertionError("auto name " + auto);
        }
        Path tmp = Files.createTempFile("grimml-marks", ".csv");
        try {
            MarkLog log = new MarkLog(tmp);
            log.append("m83e5404d", "start", 1L, 0, "notauthorised", "n0tauthorised", "abc",
                    "CHEAT", "prestige", "triggerbot", false, new String[]{"delay_min_ms=0", "miss_chance=0"});
            log.append("m83e5404d", "end", 2L, 218, "notauthorised", "n0tauthorised", "abc",
                    "CHEAT", "prestige", "triggerbot", false, new String[]{"delay_min_ms=0", "miss_chance=0"});
            var rows = log.byId("M83E5404D");
            if (rows.size() != 2 || rows.get(1).elapsedS != 218) {
                throw new AssertionError("roundtrip " + rows.size());
            }
            if (!rows.get(0).settings.contains("miss_chance=0")) {
                throw new AssertionError("settings " + rows.get(0).settings);
            }
            if (log.latestById(1).size() != 1) {
                throw new AssertionError("latest");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("mark log self-check passed");
    }
}
