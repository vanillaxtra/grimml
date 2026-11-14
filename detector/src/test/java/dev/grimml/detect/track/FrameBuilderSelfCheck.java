package dev.grimml.detect.track;

import dev.grimml.detect.model.ModelSet;

import com.google.gson.JsonObject;

import java.io.IOException;

/** Frame sequencing must match the recorder's PlayerRingBuffer; model files must be checked before use. */
public final class FrameBuilderSelfCheck {

    public static void main(String[] args) throws Exception {
        PlayerTrack.Window w = new PlayerTrack.Window(8, 16);

        // 1.21.2+ client: CLIENT_TICK_END closes each tick's frame with that tick's state
        PlayerTrack t = new PlayerTrack(1, 8, 16);
        t.flying(1, 0, 0, 1f, 0, true, true, true);
        t.clientTickEnd();
        t.flying(2, 0, 0, 2f, 0, false, true, true);
        t.clientTickEnd();
        t.snapshot(w, 0, Long.MAX_VALUE);
        check(w.n == 2 && w.yaw[0] == 100 && w.yaw[1] == 200, "tick end frames");
        check(w.flags[0] == 1 && w.flags[1] == 0, "on-ground bit");

        // older client: the first packet of the next tick commits the previous frame before its own state lands
        t = new PlayerTrack(1, 8, 16);
        t.flying(1, 0, 0, 1f, 0, true, true, true);
        t.flying(2, 0, 0, 2f, 0, true, true, true);
        t.snapshot(w, 0, Long.MAX_VALUE);
        check(w.n == 1 && w.yaw[0] == 100, "next-tick commit keeps the old state");

        // any recorder-visible packet opens a tick: an attack-only tick becomes a frame holding the last state
        t.attack(3, 1_000L);
        t.flying(4, 0, 0, 4f, 0, true, true, true);
        t.snapshot(w, 0, Long.MAX_VALUE);
        check(w.n == 3 && w.yaw[1] == 200 && w.yaw[2] == 200, "attack tick frame");
        check(w.m == 1 && w.attacks[0] == 1_000L, "attack recorded");

        // rotation-less packets keep the last rotation, position-less keep the last position
        t = new PlayerTrack(1, 8, 16);
        t.flying(1, 1.0, 2.0, 5f, 6f, true, true, true);
        t.flying(1, 9.0, 9.0, 9f, 9f, true, false, false);
        t.clientTickEnd();
        t.snapshot(w, 0, Long.MAX_VALUE);
        check(w.yaw[0] == 500 && w.pitch[0] == 600 && w.x[0] == 1000 && w.z[0] == 2000, "unchanged fields kept");

        // a second packet after CLIENT_TICK_END in the same server tick reopens a frame (recorder behaviour)
        t.flying(1, 1.0, 2.0, 7f, 6f, true, true, true);
        t.clientTickEnd();
        t.snapshot(w, 0, Long.MAX_VALUE);
        check(w.n == 2 && w.yaw[1] == 700, "reopened frame");

        // ring keeps the newest frames, oldest first; attacks are filtered by time
        t = new PlayerTrack(1, 4, 16);
        for (int i = 1; i <= 6; i++) {
            t.flying(i, 0, 0, i, 0, true, true, true);
            t.attack(i, i * 100L);
            t.clientTickEnd();
        }
        PlayerTrack.Window w4 = new PlayerTrack.Window(4, 16);
        t.snapshot(w4, 250L, 550L);
        check(w4.n == 4 && w4.yaw[0] == 300 && w4.yaw[3] == 600, "ring order");
        check(w4.m == 3 && w4.attacks[0] == 300L && w4.attacks[2] == 500L, "attack window");

        // model files: wrong checksum and path-like names are refused
        JsonObject manifest = ModelSet.parseManifest(("{\"version\":1,\"models\":[{\"module\":\"X\",\"model\":\"a.txt\","
                + "\"model_sha256\":\"00\",\"meta\":\"a.json\",\"meta_sha256\":\"00\"}]}").getBytes());
        expectFail(() -> ModelSet.build(manifest, n -> "x".getBytes()), "sha256 mismatch");
        JsonObject evil = ModelSet.parseManifest(("{\"version\":1,\"models\":[{\"module\":\"X\",\"model\":\"../a.txt\","
                + "\"model_sha256\":\"00\",\"meta\":\"a.json\",\"meta_sha256\":\"00\"}]}").getBytes());
        expectFail(() -> ModelSet.build(evil, n -> "x".getBytes()), "bad file name");

        System.out.println("frame builder: all checks passed");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void expectFail(Body b, String fragment) throws Exception {
        try {
            b.run();
        } catch (IOException e) {
            check(e.getMessage().contains(fragment), "expected '" + fragment + "', got: " + e.getMessage());
            return;
        }
        throw new AssertionError("expected failure: " + fragment);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
