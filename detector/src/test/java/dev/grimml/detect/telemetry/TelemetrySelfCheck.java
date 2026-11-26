package dev.grimml.detect.telemetry;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/** Telemetry: anonymous player ids, compact lines, split uploads, and what happens on 201 / 507 / no connection. */
public final class TelemetrySelfCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("grimml-telemetry");
        AtomicInteger reply = new AtomicInteger(201);
        List<byte[]> got = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/grimml/v1/upload", ex -> {
            got.add(ex.getRequestBody().readAllBytes());
            ids.add(ex.getRequestHeaders().getFirst("X-GrimML-Server"));
            if (reply.get() == 507) {
                ex.getResponseHeaders().add("Retry-After", "86400");
            }
            ex.sendResponseHeaders(reply.get(), -1);
            ex.close();
        });
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/grimml/v1/upload";
        Logger log = Logger.getLogger("test");
        // every fight sent (random 100%), decided as soon as it ends
        Telemetry.Sampling S = new Telemetry.Sampling(0.25, 1.0, 1.0, 6, 0, 10_000L);
        String[] names = {"a", "b", "c"};
        Telemetry t = new Telemetry(dir, new Telemetry.Settings(true, url, 60, S), log, "1.2.0", "1.21.11", () -> 10, names);
        UUID steve = UUID.randomUUID();

        // player ids: stable on this server, different on another, never the UUID
        String p = t.player(steve);
        check(p.matches("[0-9a-f]{16}") && p.equals(t.player(steve)) && !p.equals(t.player(UUID.randomUUID())), "player id " + p);
        Telemetry other = new Telemetry(Files.createTempDirectory("grimml-telemetry2"),
                new Telemetry.Settings(false, url, 60, S), log, "1.2.0", "1.21.11", () -> 10, names);
        check(!other.player(steve).equals(p), "another server makes another id");
        check(new Telemetry(dir, new Telemetry.Settings(false, url, 60, S), log, "1.2.0", "1.21.11", () -> 10, names)
                .serverId().equals(t.serverId()) && t.serverId().matches("[0-9a-f]{32}"), "server id kept");

        // numbers: 6 significant digits, whole numbers plain, NaN as null
        StringBuilder sb = new StringBuilder();
        Telemetry.number(sb, 0.123456789);
        sb.append(' ');
        Telemetry.number(sb, 3.0);
        sb.append(' ');
        Telemetry.number(sb, Double.NaN);
        sb.append(' ');
        Telemetry.number(sb, 1.0e-9);
        check(sb.toString().equals("0.123457 3 null 1E-9"), "numbers: " + sb);

        // a window joins a fight; the alert goes out at once and marks the fight; the fight goes out when it ends
        t.window(steve, 1_795_600_000_000L, new double[]{0.5, Double.NaN, 2}, List.of("MLKillAura"), new double[]{0.25},
                new double[]{0.5}, false);
        t.alert(steve, 1_795_600_001_000L, "SwapOrder", 1.0, -1, -1);
        t.tick(1_795_600_001_000L);
        check(t.buffered() == 1, "fight held until it ends: " + t.buffered());
        t.label(steve, "cheat", "macro", 1_795_600_002_000L);
        t.tick(1_795_600_060_000L);
        t.upload();
        check(got.size() == 1 && ids.get(0).equals(t.serverId()) && t.buffered() == 0, "one upload, buffer emptied");
        String body = gunzip(got.get(0));
        String[] lines = body.split("\n");
        check(lines[0].startsWith("{\"type\":\"header\",\"schema\":5,\"plugin\":\"1.2.0\",\"mc\":\"1.21.11\",\"models\":10,"
                + "\"features\":[\"a\",\"b\",\"c\"]"), "header: " + lines[0]);
        check(lines[1].equals("{\"type\":\"alert\",\"p\":\"" + p + "\",\"t\":1795600001,\"check\":\"SwapOrder\",\"conf\":1}"),
                "alert line: " + lines[1]);
        check(lines[2].equals("{\"type\":\"label\",\"p\":\"" + p + "\",\"t\":1795600002,\"label\":\"cheat\",\"kind\":\"macro\"}"),
                "label line: " + lines[2]);
        String g = lines[3].replaceAll(".*\"g\":(\\d+).*", "$1");
        check(lines[3].equals("{\"type\":\"fight\",\"g\":" + g + ",\"p\":\"" + p + "\",\"t\":1795600000,\"end\":1795600000,"
                        + "\"windows\":1,\"flagged\":0,\"band\":\"high\",\"keep\":1,\"rand\":true,\"max\":{\"MLKillAura\":0.25},"
                        + "\"label\":\"cheat\",\"by\":\"staff\",\"kind\":\"macro\"}"), "fight line: " + lines[3]);
        check(lines[4].equals("{\"type\":\"window\",\"g\":" + g + ",\"p\":\"" + p + "\",\"t\":1795600000,"
                + "\"f\":[0.5,null,2],\"s\":{\"MLKillAura\":0.25}}"), "window line: " + lines[4]);
        check(!body.contains(steve.toString()), "no UUID in the upload");

        // full receiver: lines kept, uploads paused
        reply.set(507);
        t.alert(steve, 1L, "SwapOrder", 1.0, -1, -1);
        t.upload();
        check(got.size() == 2 && t.buffered() == 1, "507 keeps the line");
        t.upload();
        check(got.size() == 2, "paused after 507");

        // no connection: kept for next time
        srv.stop(0);
        Telemetry t2 = new Telemetry(dir, new Telemetry.Settings(true, url, 60, S), log, "1.2.0", "1.21.11", () -> 10, names);
        t2.alert(steve, 1L, "SwapOrder", 1.0, -1, -1);
        t2.upload();
        check(t2.buffered() == 1, "kept when the receiver is down");

        // big buffers go up in pieces under the upload size
        String line = "x".repeat(1_000_000);
        for (int i = 0; i < 30; i++) {
            t2.add(line);
        }
        List<String> first = t2.take();
        check(first.size() == 26 && t2.buffered() == 5, "split: " + first.size() + " then " + t2.buffered());
        for (int i = 0; i < 60; i++) {
            t2.add(line);
        }
        check(t2.buffered() <= Telemetry.MAX_BUFFER_BYTES / 1_000_000, "buffer capped: " + t2.buffered());
        t.shutdown();
        t2.shutdown();
        System.out.println("telemetry: all checks passed");
    }

    private static String gunzip(byte[] b) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
