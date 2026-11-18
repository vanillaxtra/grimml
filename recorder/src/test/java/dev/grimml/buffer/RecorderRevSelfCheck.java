package dev.grimml.buffer;

import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;

// recorder rev 2 behaviour: flags clear, PARTIAL is not sticky, lost events are reported
public final class RecorderRevSelfCheck {

    public static void main(String[] args) {
        TickClock clock = new TickClock();
        PlayerRingBuffer buf = new PlayerRingBuffer(1L, 2L, 3, 32, 16, clock);

        buf.setLive(RecordTypes.FLAG_IN_WATER | RecordTypes.FLAG_BLOCKING);
        buf.updateLive(RecordTypes.FLAG_IN_WATER | RecordTypes.FLAG_ELYTRA, RecordTypes.FLAG_ELYTRA);
        if (buf.liveFlags != (RecordTypes.FLAG_BLOCKING | RecordTypes.FLAG_ELYTRA)) {
            throw new AssertionError("live flags " + buf.liveFlags);
        }
        buf.clearLive(RecordTypes.FLAG_ELYTRA);
        if (buf.liveFlags != RecordTypes.FLAG_BLOCKING) {
            throw new AssertionError("clear live");
        }

        // wrap the frame ring several times: that alone must not mark anything partial
        for (int t = 0; t < 200; t++) {
            buf.nextOrder(t);
            buf.applyFlying(t, 0, 1, t, 0, 0, 0, 0, true, false, true, true);
            buf.markClientTickEnd();
        }
        long now = System.nanoTime();
        if ((buf.validityIn(now - 8_000_000_000L, now) & RecordTypes.VALID_PARTIAL) != 0) {
            throw new AssertionError("partial after a normal ring wrap");
        }

        // few events, all inside the window: complete
        long base = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            buf.writeEvent(RecordTypes.SWING, 1, 0, 1, base + i, 0, 0, 0, 0, 0, 0);
        }
        CombatSnapshot snap = new CombatSnapshot(32, 16);
        snap.reset();
        snap.copyFrom(buf, base - 1_000L, base + 1_000_000L);
        if (snap.eventCount != 5 || (snap.validity & RecordTypes.VALID_PARTIAL) != 0) {
            throw new AssertionError("complete window flagged: events " + snap.eventCount + " validity " + snap.validity);
        }

        // more events than the ring holds inside one window: the oldest were overwritten, so partial
        for (int i = 0; i < 40; i++) {
            buf.writeEvent(RecordTypes.SWING, 1, 0, 1, base + 100 + i, 0, 0, 0, 0, 0, 0);
        }
        snap.reset();
        snap.copyFrom(buf, base - 1_000L, base + 1_000_000L);
        if ((snap.validity & RecordTypes.VALID_PARTIAL) == 0) {
            throw new AssertionError("lost events not flagged");
        }
        snap.reset();
        if (snap.validity != 0) {
            throw new AssertionError("reset keeps validity");
        }
        System.out.println("recorder rev self-check passed");
    }
}
