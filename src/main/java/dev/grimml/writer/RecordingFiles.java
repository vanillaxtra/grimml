package dev.grimml.writer;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

public final class RecordingFiles {

    private final Path recordingsDir;
    private final String datePrefix;

    public RecordingFiles(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.recordingsDir = plugin.getDataFolder().toPath().resolve(config.recordingsDir()).toAbsolutePath();
        Files.createDirectories(recordingsDir);
        this.datePrefix = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    public Path dir() {
        return recordingsDir;
    }

    public Path newFilePath(long anonPlayerId, long sessionId, String markId) {
        return recordingsDir.resolve(fileName(datePrefix, markId, anonPlayerId, sessionId,
                UUID.randomUUID().toString().substring(0, 8)));
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
