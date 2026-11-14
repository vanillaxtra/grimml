package dev.grimml.writer;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

public final class RecordingFiles {

    private final Path recordingsDir;
    // writer thread only
    private long prefixMinute = -1L;
    private String prefix = "";

    public RecordingFiles(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.recordingsDir = plugin.getDataFolder().toPath().resolve(config.recordingsDir()).toAbsolutePath();
        Files.createDirectories(recordingsDir);
    }

    public Path dir() {
        return recordingsDir;
    }

    public Path newFilePath(long anonPlayerId, long sessionId, String markId) {
        return recordingsDir.resolve(fileName(datePrefix(), markId, anonPlayerId, sessionId,
                UUID.randomUUID().toString().substring(0, 8)));
    }

    // the day the window is written. this used to be computed once at server start, so every file carried the
    // last restart date however many days the server had been up
    private String datePrefix() {
        long minute = System.currentTimeMillis() / 60_000L;
        if (minute != prefixMinute) {
            prefix = LocalDate.now().toString();
            prefixMinute = minute;
        }
        return prefix;
    }

    public static String fileName(String datePrefix, String markId, long anonPlayerId, long sessionId, String rand) {
        if (markId != null && !markId.isEmpty()) {
            return datePrefix + "_" + markId + "_" + rand + ".gmlml";
        }
        return datePrefix + "_" + Long.toUnsignedString(anonPlayerId, 36)
                + "_" + Long.toUnsignedString(sessionId, 36)
                + "_" + rand + ".gmlml";
    }
}
