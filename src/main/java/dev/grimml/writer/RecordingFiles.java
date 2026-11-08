package dev.grimml.writer;

import dev.grimml.GrimMLPlugin;
import dev.grimml.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class RecordingFiles {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    private final Path recordingsDir;
    private final String rotation;
    private Path lastDir;

    public RecordingFiles(GrimMLPlugin plugin, PluginConfig config) throws IOException {
        this.recordingsDir = plugin.getDataFolder().toPath().resolve(config.recordingsDir()).toAbsolutePath();
        this.rotation = config.rotation();
        Files.createDirectories(recordingsDir);
    }

    public Path dir() {
        return recordingsDir;
    }

    // writer thread only
    public Path newFilePath(long anonPlayerId, long sessionId, String markId) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String day = DAY.format(now);
        Path dir = recordingsDir.resolve(subDir(rotation, day, HOUR.format(now)));
        if (!dir.equals(lastDir)) {
            Files.createDirectories(dir);
            lastDir = dir;
        }
        return dir.resolve(fileName(day, markId, anonPlayerId, sessionId, UUID.randomUUID().toString().substring(0, 8)));
    }

    public static String subDir(String rotation, String day, String hour) {
        return switch (rotation) {
            case "hourly" -> day + "/" + hour;
            case "daily" -> day;
            default -> "";
        };
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
