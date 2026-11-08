package dev.grimml.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

// moves finished day folders out of the live recordings dir on the grimml-disk thread
public final class Offloader {

    private final Path source;
    private final Path target;
    private final int afterDays;
    private final Logger log;

    public Offloader(Path source, Path target, int afterDays, Logger log) {
        this.source = source;
        this.target = target;
        this.afterDays = afterDays;
        this.log = log;
    }

    public int run(LocalDate today) {
        if (!Files.isDirectory(source)) {
            return 0;
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(source, Files::isDirectory)) {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            log.warning("offload scan failed: " + e.getMessage());
            return 0;
        }
        int moved = 0;
        for (String day : due(names, today, afterDays)) {
            try {
                moveTree(source.resolve(day), target.resolve(day));
                moved++;
                log.info("offloaded recordings " + day + " to " + target);
            } catch (IOException e) {
                log.warning("offload " + day + " failed: " + e.getMessage());
            }
        }
        return moved;
    }

    public static List<String> due(List<String> dirNames, LocalDate today, int afterDays) {
        LocalDate cutoff = today.minusDays(Math.max(1, afterDays) - 1L);
        List<String> out = new ArrayList<>();
        for (String name : dirNames) {
            try {
                if (LocalDate.parse(name).isBefore(cutoff)) {
                    out.add(name);
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        out.sort(null);
        return out;
    }

    // per file move so a cross-device target or a half moved day resumes cleanly
    private static void moveTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                if (Files.exists(dest) && Files.size(dest) == attrs.size()) {
                    Files.delete(file);
                } else {
                    Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
