package dev.grimml.writer;

import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.format.RecordTypes;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class DropCounters {

    public final AtomicLong windows = new AtomicLong();
    public final AtomicLong tornFrames = new AtomicLong();
    public final AtomicLong sidecarLines = new AtomicLong();
    public final AtomicLongArray byType = new AtomicLongArray(32);

    // every record inside a window dropped on a full queue counts against its type
    public void window(CombatSnapshot snap) {
        windows.incrementAndGet();
        if (snap.frameCount > 0) {
            byType.addAndGet(RecordTypes.FRAME, snap.frameCount);
        }
        for (int i = 0; i < snap.eventCount; i++) {
            int t = snap.eType[i];
            if (t >= 0 && t < byType.length()) {
                byType.incrementAndGet(t);
            }
        }
    }

    public String byTypeSummary() {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < byType.length(); t++) {
            long n = byType.get(t);
            if (n > 0) {
                sb.append(sb.isEmpty() ? "" : " ").append(RecordTypes.name(t)).append('=').append(n);
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    public void torn(int n) {
        tornFrames.addAndGet(n);
    }

    public void sidecar() {
        sidecarLines.incrementAndGet();
    }

    public String summary() {
        return "dropped windows: " + windows.get() + " torn slots: " + tornFrames.get() + " sidecar lines: " + sidecarLines.get();
    }
}
