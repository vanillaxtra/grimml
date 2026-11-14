package dev.grimml.grim;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GrimCheckCatalogSelfCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("grimml-checks");
        Path file = dir.resolve("grim-checks.json");
        GrimCheckCatalog cat = new GrimCheckCatalog(file);
        if (cat.learn("Reach") != true) {
            throw new AssertionError("first learn");
        }
        if (cat.learn("Reach")) {
            throw new AssertionError("dup");
        }
        cat.learn("AimModulo360");
        cat.flush();
        GrimCheckCatalog b = new GrimCheckCatalog(file);
        b.load();
        if (!b.names().equals(cat.names())) {
            throw new AssertionError("roundtrip " + b.names());
        }
        if (!b.complete("aim").equals(java.util.List.of("AimModulo360"))) {
            throw new AssertionError("complete");
        }
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
        System.out.println("grim check catalog self-check passed");
    }
}
