package dev.grimml.detect.telemetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opt-in telemetry: the scored fight windows (features and model scores) and the alerts, sent every few minutes as
 * gzipped JSON lines to the GrimML receiver, so the models can be checked and retrained on more players.
 *
 * Never sent: player names, UUIDs, IPs, chat, coordinates. Players are a salted hash that only this server can make
 * (the salt stays in plugins/GrimML/telemetry/), so the same player on two servers cannot be matched.
 * The server is a random id made once (plugins/GrimML/telemetry/server-id); delete that folder to get a new one.
 *
 * Lines are built on the scan thread and buffered (capped, oldest dropped); uploads run on their own thread.
 */
public final class Telemetry {

    public record Settings(boolean enabled, String url, int everyMinutes, Sampling sampling) {
    }

    /** Which fights are sent: see FightSampler. */
    public record Sampling(double unsureShare, double balance, double randomShare, int perPlayerHour, int holdMinutes,
                           long gapMs) {
        FightSampler.Sampling toSampler() {
            return new FightSampler.Sampling(unsureShare, balance, randomShare, perPlayerHour, holdMinutes, gapMs);
        }
    }

    // fixed on purpose: every server sends to the same receiver, sampled the same way, so the data stays comparable.
    // HTTPS on 443 through Cloudflare (which forwards to the receiver's own port), because many game hosts block
    // outgoing connections on other ports.
    public static final String URL = "https://telemetry.notauthorised.lol/grimml/v1/upload";
    public static final Sampling SAMPLING = new Sampling(0.25, 1.0, 0.03, 6, 5, 30_000L);

    /** Labels staff can give: what the player is, and for cheat what kind. */
    public static final List<String> LABELS = List.of("cheat", "legit");
    public static final List<String> KINDS = List.of("killaura", "aimassist", "triggerbot", "silentaim", "reach",
            "macro", "lunge", "other");

    static final int MAX_BUFFER_BYTES = 48 * 1024 * 1024;  // unzipped, about 3-4 busy hours
    static final int MAX_UPLOAD_BYTES = 24 * 1024 * 1024;  // unzipped per upload; gzip brings it well under 8 MB
    private static final int MAX_UPLOADS_PER_RUN = 3;
    private static final MathContext DIGITS = new MathContext(6);

    private final Logger log;
    private final String pluginVersion;
    private final String mcVersion;
    private final Supplier<Integer> modelsVersion;
    private final String[] featureNames;
    private final String serverId;
    private final byte[] salt;
    private final ArrayDeque<String> buffer = new ArrayDeque<>();
    private long bufferBytes;
    private long dropped;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GrimML-telemetry");
        t.setDaemon(true);
        return t;
    });
    private volatile Settings settings;
    private volatile long pausedUntilMs;
    private java.util.concurrent.ScheduledFuture<?> task;
    private boolean warned;
    private final FightSampler sampler;

    public Telemetry(Path dir, Settings settings, Logger log, String pluginVersion, String mcVersion,
                     Supplier<Integer> modelsVersion, String[] featureNames) throws IOException {
        this.log = log;
        this.pluginVersion = pluginVersion;
        this.mcVersion = mcVersion;
        this.modelsVersion = modelsVersion;
        this.featureNames = featureNames;
        Files.createDirectories(dir);
        this.serverId = readOrMake(dir.resolve("server-id"), 16);
        this.salt = HexFormat.of().parseHex(readOrMake(dir.resolve("salt"), 32));
        this.sampler = new FightSampler(settings.sampling().toSampler(), this::addAll, new java.util.Random(),
                System.currentTimeMillis());
        settings(settings);
    }

    private static String readOrMake(Path f, int bytes) throws IOException {
        if (Files.exists(f)) {
            String s = Files.readString(f).trim().toLowerCase();
            if (s.matches("[0-9a-f]{" + bytes * 2 + "}")) {
                return s;
            }
        }
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        String s = HexFormat.of().formatHex(b);
        Files.writeString(f, s);
        return s;
    }

    public synchronized void settings(Settings s) {
        this.settings = s;
        sampler.settings(s.sampling().toSampler());
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (!s.enabled()) {
            sampler.clear();
            synchronized (buffer) {
                buffer.clear();
                bufferBytes = 0;
            }
            return;
        }
        long every = Math.max(5, s.everyMinutes());
        task = exec.scheduleWithFixedDelay(this::upload, every, every, TimeUnit.MINUTES);
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public String serverId() {
        return serverId;
    }

    // the same player always gets the same id on this server, and a different one on every other server
    String player(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(id.toString().getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void number(StringBuilder sb, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            sb.append(new BigDecimal(v).round(DIGITS).stripTrailingZeros().toString());
        }
    }

    // scan thread: one scored window. checks, scores and each model's window threshold line up.
    // It joins the player's fight; the fight is sent or not when it ends (FightSampler).
    public void window(UUID id, long epochMs, double[] features, List<String> checks, double[] scores,
                       double[] thresholds, int attacks, int events, boolean trusted) {
        if (!settings.enabled()) {
            return;
        }
        String p = player(id);
        boolean flagged = false;
        Map<String, Double> byCheck = new java.util.LinkedHashMap<>();
        for (int i = 0; i < checks.size(); i++) {
            flagged |= scores[i] >= thresholds[i];
            byCheck.put(checks.get(i), scores[i]);
        }
        sampler.window(p, epochMs, trusted, flagged, byCheck, g -> windowLine(g, p, epochMs, features, checks, scores, attacks,
                events));
    }

    private static String windowLine(long g, String p, long epochMs, double[] features, List<String> checks,
                                     double[] scores, int attacks, int events) {
        StringBuilder sb = new StringBuilder(features.length * 9 + 140);
        sb.append("{\"type\":\"window\",\"g\":").append(g).append(",\"p\":\"").append(p).append("\",\"t\":")
                .append(epochMs / 1000).append(",\"a\":").append(attacks).append(",\"e\":").append(events)
                .append(",\"f\":[");
        for (int i = 0; i < features.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            number(sb, features[i]);
        }
        sb.append("],\"s\":{");
        for (int i = 0; i < checks.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(checks.get(i)).append("\":");
            number(sb, scores[i]);
        }
        return sb.append("}}").toString();
    }

    // scan thread, after every scan
    public void tick(long epochMs) {
        if (settings.enabled()) {
            sampler.tick(epochMs);
        }
    }

    // main thread: staff label for a player. Sent as its own line, and the player's waiting and next 30 minutes of
    // fights are kept with it. Returns how many waiting fights it reached.
    public int label(UUID id, String label, String kind, long epochMs) {
        if (!settings.enabled()) {
            return 0;
        }
        String p = player(id);
        add("{\"type\":\"label\",\"p\":\"" + p + "\",\"t\":" + epochMs / 1000 + ",\"label\":\"" + label + "\""
                + (kind == null ? "" : ",\"kind\":\"" + kind + "\"") + "}");
        return sampler.label(p, label, kind, epochMs);
    }

    // main thread: the player was banned (how: "vanilla" for the server's ban list, "kick-message" for a kick whose
    // message mentions a ban, as ban plugins send). Sent as its own line; the player's open and waiting fights are kept.
    // Not proof of cheating (bans for chat or alts look the same), so training uses it to leave the player out of
    // false-flag counts and to see which flagged players got banned, never as a cheat label.
    public void ban(UUID id, String how, long epochMs) {
        if (!settings.enabled()) {
            return;
        }
        String p = player(id);
        add("{\"type\":\"ban\",\"p\":\"" + p + "\",\"t\":" + epochMs / 1000 + ",\"how\":\"" + how + "\"}");
        sampler.ban(p);
    }

    public String sampling() {
        return sampler.summary();
    }

    // an alert: ML checks with the fight's counts, rule checks with -1
    public void alert(UUID id, long epochMs, String check, double confidence, int flagged, int windows) {
        if (!settings.enabled()) {
            return;
        }
        String p = player(id);
        sampler.alert(p, epochMs);
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"type\":\"alert\",\"p\":\"").append(p).append("\",\"t\":").append(epochMs / 1000)
                .append(",\"check\":\"").append(check).append("\",\"conf\":");
        number(sb, confidence);
        if (windows >= 0) {
            sb.append(",\"flagged\":").append(flagged).append(",\"windows\":").append(windows);
        }
        add(sb.append('}').toString());
    }

    void add(String line) {
        synchronized (buffer) {
            buffer.addLast(line);
            bufferBytes += line.length() + 1;
            while (bufferBytes > MAX_BUFFER_BYTES && !buffer.isEmpty()) {
                bufferBytes -= buffer.removeFirst().length() + 1;
                dropped++;
            }
        }
    }

    private void addAll(List<String> lines) {
        for (String l : lines) {
            add(l);
        }
    }

    int buffered() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    // takes up to MAX_UPLOAD_BYTES of the oldest lines
    List<String> take() {
        List<String> out = new ArrayList<>();
        long size = 0;
        synchronized (buffer) {
            while (!buffer.isEmpty() && (out.isEmpty() || size + buffer.peekFirst().length() + 1 <= MAX_UPLOAD_BYTES)) {
                String l = buffer.removeFirst();
                size += l.length() + 1;
                out.add(l);
            }
            bufferBytes -= size;
        }
        return out;
    }

    // puts lines that could not be sent back at the front
    private void giveBack(List<String> lines) {
        synchronized (buffer) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                buffer.addFirst(lines.get(i));
                bufferBytes += lines.get(i).length() + 1;
            }
            while (bufferBytes > MAX_BUFFER_BYTES && !buffer.isEmpty()) {
                bufferBytes -= buffer.removeLast().length() + 1;
                dropped++;
            }
        }
    }

    byte[] body(List<String> lines) throws IOException {
        StringBuilder h = new StringBuilder("{\"type\":\"header\",\"schema\":5,\"plugin\":\"").append(pluginVersion)
                .append("\",\"mc\":\"").append(mcVersion.replace("\"", "")).append("\",\"models\":")
                .append(modelsVersion.get()).append(",\"features\":[");
        for (int i = 0; i < featureNames.length; i++) {
            h.append(i > 0 ? "," : "").append('"').append(featureNames[i]).append('"');
        }
        h.append("],\"lines\":").append(lines.size()).append("}\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(h.toString().getBytes(StandardCharsets.UTF_8));
            for (String l : lines) {
                gz.write(l.getBytes(StandardCharsets.UTF_8));
                gz.write('\n');
            }
        }
        return bytes.toByteArray();
    }

    // sends now on the telemetry thread and says how it went (/grimml telemetry)
    public void sendNow(java.util.function.Consumer<String> done) {
        exec.execute(() -> done.accept(upload()));
    }

    public String lastResult() {
        return lastResult;
    }

    private volatile String lastResult = "nothing sent yet";

    // telemetry thread: sends what is buffered, at most a few uploads per run; returns what happened
    public String upload() {
        String r = uploadOnce();
        lastResult = r + " (" + java.time.LocalTime.now().withNano(0) + ")";
        return r;
    }

    private String uploadOnce() {
        Settings s = settings;
        if (!s.enabled()) {
            return "telemetry is off";
        }
        if (System.currentTimeMillis() < pausedUntilMs) {
            return "paused, the receiver asked to wait (full or busy)";
        }
        int sent = 0;
        for (int n = 0; n < MAX_UPLOADS_PER_RUN && buffered() > 0; n++) {
            List<String> lines = take();
            int status;
            String why = "";
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(s.url()))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/gzip")
                        .header("X-GrimML-Server", serverId)
                        .header("X-GrimML-Version", pluginVersion)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body(lines))).build();
                HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
                status = r.statusCode();
                if (status == 429 || status == 507 || status == 503) {
                    long wait = r.headers().firstValueAsLong("Retry-After").orElse(3600);
                    pausedUntilMs = System.currentTimeMillis() + Math.min(wait, 86_400) * 1000;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                giveBack(lines);
                return "interrupted, kept for next time";
            } catch (Exception e) {
                status = -1;
                why = reason(e);
                if (!warned) {
                    log.info("telemetry upload to " + s.url() + " failed, will retry: " + why);
                    warned = true;
                }
            }
            if (status == 201) {
                warned = false;
                sent += lines.size();
                continue;
            }
            if (status == -1 || status == 429 || status >= 500) {
                giveBack(lines); // try again next time
                return status == -1 ? "receiver not reachable (" + why + "), kept for next time"
                        : "receiver said HTTP " + status + ", kept for next time";
            }
            log.warning("telemetry upload refused (HTTP " + status + "), " + lines.size() + " lines dropped");
            return "receiver refused the upload (HTTP " + status + ")";
        }
        return sent == 0 ? "nothing to send" : "sent " + sent + " lines";
    }

    // the exception and its causes, e.g. "ConnectException / ClosedChannelException", so /grimml telemetry shows why
    static String reason(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = e; c != null && sb.length() < 300; c = c.getCause() == c ? null : c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(c.getClass().getSimpleName());
            String m = c.getMessage();
            if (m != null && !m.isBlank() && !m.equals(String.valueOf(c.getCause()))) {
                sb.append(": ").append(m);
            }
        }
        return sb.toString();
    }

    public Map<String, Object> status() {
        synchronized (buffer) {
            return Map.of("buffered", buffer.size(), "dropped", dropped);
        }
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
