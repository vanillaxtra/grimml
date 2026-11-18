package dev.grimml.grim;

import dev.grimml.format.RecordTypes;

public final class LagFilterSelfCheck {

    public static void main(String[] args) {
        GrimWeights w = GrimWeights.empty();
        FlagQueue.Slot s = new FlagQueue.Slot();
        s.nanos = 20_000_000_000L;
        s.ping = 40;
        s.jitter = 5;
        if (LagFilter.skip(s, 10, w)) {
            throw new AssertionError("clean flag");
        }
        s.ping = 250;
        if (!LagFilter.skip(s, 10, w)) {
            throw new AssertionError("high ping");
        }
        s.ping = 40;
        s.joinNanos = 15_000_000_000L;
        if (!LagFilter.skip(s, 10, w)) {
            throw new AssertionError("join settle");
        }
        s.joinNanos = 0;
        s.validity = RecordTypes.VALID_SETBACK;
        if (!LagFilter.skip(s, 10, w)) {
            throw new AssertionError("setback validity");
        }
        s.validity = 0;
        if (!LagFilter.skip(s, 80, w)) {
            throw new AssertionError("mspt");
        }
        System.out.println("lag filter self-check passed");
    }
}
