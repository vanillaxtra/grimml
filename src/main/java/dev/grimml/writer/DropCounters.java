package dev.grimml.writer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class DropCounters {

    public final AtomicLong windows = new AtomicLong();
    public final AtomicLong tornFrames = new AtomicLong();
    public final AtomicLongArray byType = new AtomicLongArray(32);

    public void window() {
        windows.incrementAndGet();
    }

    public void torn() {
        tornFrames.incrementAndGet();
    }

    public void type(int t) {
        if (t >= 0 && t < byType.length()) {
            byType.incrementAndGet(t);
        }
    }

    public String summary() {
        return "dropped windows: " + windows.get() + " torn frames: " + tornFrames.get();
    }
}
