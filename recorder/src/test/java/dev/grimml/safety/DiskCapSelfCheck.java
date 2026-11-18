package dev.grimml.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DiskCapSelfCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("grimml-disk");
        try {
            Files.write(dir.resolve("a.gmlml"), new byte[100]);
            Path nested = Files.createDirectory(dir.resolve("sub"));
            Files.write(nested.resolve("b.gmlml"), new byte[50]);
            long size = DiskCapGuard.directorySize(dir);
            long missing = DiskCapGuard.directorySize(dir.resolve("nope"));
            if (size != 150L || missing != 0L) {
                throw new AssertionError("expected 150 and 0, got " + size + " and " + missing);
            }
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        System.out.println("disk cap self-check passed");
    }
}
