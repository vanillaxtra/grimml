package dev.grimml.detect.track;

import dev.grimml.detect.features.Window4;
import dev.grimml.detect.model.ModelSet;

import com.google.gson.JsonObject;

import java.io.IOException;

/** Frame sequencing must match the recorder's PlayerRingBuffer; model files must be checked before use. */
public final class FrameBuilderSelfCheck {

    public static void main(String[] args) throws Exception {
        PlayerTrack.Window w = new PlayerTrack.Window(8, 16);
        Window4 w4 = new Window4(8, 16);

        // 1.21.2+ client: CLIENT_TICK_END closes each tick's frame with that tick's state
        PlayerTrack t = new PlayerTrack(1, 8, 16);
        t.flying(1, 0, 0, 0, 1f, 0, true, true, true);
        t.clientTickEnd();
        t.flying(2, 0, 0, 0, 2f, 0, false, true, true);
        t.clientTickEnd();
        t.snapshot(w, w4, 0, Long.MAX_VALUE);
        check(w.n == 2 && w.yaw[0] == 100 && w.yaw[1] == 200, "tick end frames");
        check(w.flags[0] == 1 && w.flags[1] == 0, "on-ground bit");

        // older client: the first packet of the next tick commits the previous frame before its own state lands
        t = new PlayerTrack(1, 8, 16);
        t.flying(1, 0, 0, 0, 1f, 0, true, true, true);
        t.flying(2, 0, 0, 0, 2f, 0, true, true, true);
        t.snapshot(w, w4, 0, Long.MAX_VALUE);
        check(w.n == 1 && w.yaw[0] == 100, "next-tick commit keeps the old state");

        // any recorder-visible packet opens a tick: an attack-only tick becomes a frame holding the last state
        t.attack(3, 1_000L, 99);
        t.flying(4, 0, 0, 0, 4f, 0, true, true, true);
        t.snapshot(w, w4, 0, Long.MAX_VALUE);
        check(w.n == 3 && w.yaw[1] == 200 && w.yaw[2] == 200, "attack tick frame");
        check(w.m == 1 && w.attacks[0] == 1_000L, "attack recorded");

        // rotation-less packets keep the last rotation, position-less keep the last position
        t = new PlayerTrack(1, 8, 16);
        t.flying(1, 1.0, 0, 2.0, 5f, 6f, true, true, true);
        t.flying(1, 9.0, 9.0, 9.0, 9f, 9f, true, false, false);
        t.clientTickEnd();
        t.snapshot(w, w4, 0, Long.MAX_VALUE);
        check(w.yaw[0] == 500 && w.pitch[0] == 600 && w.x[0] == 1000 && w.z[0] == 2000, "unchanged fields kept");

        // a second packet after CLIENT_TICK_END in the same server tick reopens a frame (recorder behaviour)
        t.flying(1, 1.0, 0, 2.0, 7f, 6f, true, true, true);
        t.clientTickEnd();
        t.snapshot(w, w4, 0, Long.MAX_VALUE);
        check(w.n == 2 && w.yaw[1] == 700, "reopened frame");

        // ring keeps the newest frames, oldest first; attacks are filtered by time
        t = new PlayerTrack(1, 4, 16);
        for (int i = 1; i <= 6; i++) {
            t.flying(i, 0, 0, 0, i, 0, true, true, true);
            t.attack(i, i * 100L, 99);
            t.clientTickEnd();
        }
        PlayerTrack.Window wr = new PlayerTrack.Window(4, 16);
        Window4 wr4 = new Window4(4, 32);
        t.snapshot(wr, wr4, 250L, 550L);
        check(wr.n == 4 && wr.yaw[0] == 300 && wr.yaw[3] == 600, "ring order");
        check(wr.m == 3 && wr.attacks[0] == 300L && wr.attacks[2] == 500L, "attack window");

        // schema 4 live capture: opponent block, client ticks, cooldown, target kind, inputs, transactions
        PlayerTrack me = new PlayerTrack(1, 8, 64);
        PlayerTrack opp = new PlayerTrack(2, 8, 64);
        me.peers = id -> id == 2 ? opp : null;
        opp.flying(10, 2.0, 0, 3.0, 90f, 0, true, true, true);
        opp.clientTickEnd();
        opp.setLive(PlayerTrack.L_BLOCKING, true);
        me.opponentEntityId = 2;
        me.flying(10, 0, 0, 0, 12.345f, -3.5f, true, true, true);
        me.swing(10, 1_000L);
        me.input(10, 1_100L, 1 | 16);
        me.clientTickEnd();
        me.flying(13, 0, 0, 0, 13f, -3.5f, true, true, true);
        me.attack(13, 2_000L, 2);
        me.rttSent(13, 2_100L, -7, 1);
        me.pong(14, 2_900L, -7);
        me.clientTickEnd();
        Window4 s4 = new Window4(8, 64);
        me.snapshot(new PlayerTrack.Window(8, 64), s4, 0, Long.MAX_VALUE);
        check(s4.n == 2 && s4.hasRot[0] && s4.yawExact[0] == 12.345f, "exact rotation");
        check(s4.clientTick[0] == 0 && s4.clientTick[1] == 1, "client ticks");
        check(s4.hasOpp[0] && s4.oppDx[0] == 2000 && s4.oppDz[0] == 3000, "opponent position");
        check((s4.oppState[0] & 8) != 0 && (s4.oppState[0] & 1) != 0 && s4.oppBlockAgo[0] >= 0, "opponent state");
        int att = -1;
        for (int e = 0; e < s4.m; e++) if (s4.eType[e] == Window4.ATTACK) att = e;
        check(att >= 0 && (s4.eG[att] & 0xFF) == 1 && (s4.eG[att] >> 8) == 3 && s4.eB[att] == 2, "attack kind and cooldown");
        check(s4.eClientTick[att] == 1, "event client tick");
        boolean sent = false, reply = false, input = false;
        for (int e = 0; e < s4.m; e++) {
            sent |= s4.eType[e] == Window4.RTT_SENT && s4.eA[e] == -7 && s4.eB[e] == 1;
            reply |= s4.eType[e] == Window4.RTT && s4.eA[e] == -7 && s4.eC[e] == 1;
            input |= s4.eType[e] == Window4.INPUT && s4.eA[e] == (1 | 16);
        }
        check(sent && reply && input, "transactions and inputs");

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
