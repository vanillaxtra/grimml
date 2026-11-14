package dev.grimml.detect.model;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Where models come from, newest wins:
 *  1. the copy bundled in the jar (models/ at build time),
 *  2. the last downloaded set in plugins/GrimMLDetector/models/,
 *  3. the manifest URL (checked on start and every few minutes, off the main thread).
 * A download is only used once every file passed its sha256 and the whole set parsed; a bad update
 * keeps the old models running.
 */
public final class ModelStore {

    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final Path cacheDir;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile ModelSet current = ModelSet.EMPTY;
    private volatile String lastError = "";

    public ModelStore(Path cacheDir, Logger log) {
        this.cacheDir = cacheDir;
        this.log = log;
    }

    public ModelSet current() {
        return current;
    }

    public String lastError() {
        return lastError;
    }

    /** Bundled set, then the cached download if it is newer. */
    public void loadLocal() {
        try {
            byte[] m = bundled("manifest.json");
            if (m != null) {
                use(ModelSet.build(ModelSet.parseManifest(m), this::bundledOrThrow), "bundled");
            }
        } catch (Exception e) {
            fail("bundled models are broken: " + e.getMessage());
        }
        Path manifest = cacheDir.resolve("manifest.json");
        if (Files.isRegularFile(manifest)) {
            try {
                JsonObject mj = ModelSet.parseManifest(Files.readAllBytes(manifest));
                if (mj.get("version").getAsInt() > current.version()) {
                    use(ModelSet.build(mj, name -> Files.readAllBytes(cacheDir.resolve(ModelSet.safeName(name)))), "cached");
                }
            } catch (Exception e) {
                fail("cached models are broken, ignoring them: " + e.getMessage());
            }
        }
    }

    /** Fetches the manifest and installs a newer version. Blocking: call from a background thread. */
    public boolean update(String manifestUrl, Consumer<ModelSet> onNew) {
        if (manifestUrl == null || manifestUrl.isBlank()) {
            return false;
        }
        try {
            URI base = URI.create(manifestUrl.trim());
            byte[] mBytes = get(base);
            JsonObject mj = ModelSet.parseManifest(mBytes);
            int version = mj.get("version").getAsInt();
            if (version <= current.version()) {
                return false;
            }
            Map<String, byte[]> got = new HashMap<>();
            ModelSet set = ModelSet.build(mj, name -> {
                byte[] b = get(base.resolve(ModelSet.safeName(name)));
                got.put(name, b);
                return b;
            });
            Files.createDirectories(cacheDir);
            for (var e : got.entrySet()) {
                atomicWrite(cacheDir.resolve(e.getKey()), e.getValue());
            }
            atomicWrite(cacheDir.resolve("manifest.json"), mBytes); // last, so a half-written cache is never "newer"
            use(set, "downloaded from " + base.getHost());
            onNew.accept(set);
            return true;
        } catch (Exception e) {
            fail("model update failed, keeping version " + current.version() + ": " + e.getMessage());
            return false;
        }
    }

    private void use(ModelSet set, String from) {
        current = set;
        lastError = "";
        StringBuilder sb = new StringBuilder();
        for (ModuleModel m : set.models()) {
            sb.append(' ').append(m.checkName()).append(m.enabled() ? "" : "(off)");
        }
        log.info("Models v" + set.version() + " (" + from + "):" + sb);
    }

    private void fail(String msg) {
        lastError = msg;
        log.warning(msg);
    }

    private byte[] get(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("only https model urls are allowed: " + uri);
        }
        HttpResponse<InputStream> r;
        try {
            r = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", "grimml-detector").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        }
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode() + " for " + uri);
            }
            byte[] b = in.readNBytes(MAX_FILE_BYTES + 1);
            if (b.length > MAX_FILE_BYTES) {
                throw new IOException("file too large: " + uri);
            }
            return b;
        }
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] bundled(String name) throws IOException {
        try (InputStream in = ModelStore.class.getClassLoader().getResourceAsStream("models/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private byte[] bundledOrThrow(String name) throws IOException {
        byte[] b = bundled(name);
        if (b == null) {
            throw new IOException("bundled file missing: " + name);
        }
        return b;
    }
}
