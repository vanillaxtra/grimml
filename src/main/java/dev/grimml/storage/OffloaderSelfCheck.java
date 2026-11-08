package dev.grimml.storage;

import dev.grimml.writer.RecordingFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

public final class OffloaderSelfCheck {

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 8);
        List<String> due = Offloader.due(List.of("2026-11-08", "2026-11-07", "2026-11-05", "maps", "2026-13-01"), today, 1);
        if (!due.equals(List.of("2026-11-05", "2026-11-07"))) {
            throw new AssertionError("after-days 1 picked " + due);
        }
        if (!Offloader.due(List.of("2026-11-08", "2026-11-07", "2026-11-05"), today, 2).equals(List.of("2026-11-05"))) {
            throw new AssertionError("after-days 2");
        }
        if (!RecordingFiles.subDir("hourly", "2026-11-08", "07").equals("2026-11-08/07")
                || !RecordingFiles.subDir("daily", "2026-11-08", "07").equals("2026-11-08")
                || !RecordingFiles.subDir("none", "2026-11-08", "07").isEmpty()) {
            throw new AssertionError("rotation subdir");
        }
        Path root = Files.createTempDirectory("grimml-offload");
        Path src = root.resolve("rec");
        Path dst = root.resolve("off");
        Files.createDirectories(src.resolve("2026-11-07/13"));
        Files.createDirectories(src.resolve("2026-11-08/01"));
        Files.writeString(src.resolve("2026-11-07/13/a.gmlml"), "x");
        Files.writeString(src.resolve("sessions.idx"), "y");
        Logger quiet = Logger.getAnonymousLogger();
        quiet.setUseParentHandlers(false);
        int moved = new Offloader(src, dst, 1, quiet).run(today);
        if (moved != 1 || !Files.exists(dst.resolve("2026-11-07/13/a.gmlml")) || Files.exists(src.resolve("2026-11-07"))
                || !Files.exists(src.resolve("2026-11-08/01")) || !Files.exists(src.resolve("sessions.idx"))) {
            throw new AssertionError("offload move");
        }
        System.out.println("offloader self-check passed");
    }
}
