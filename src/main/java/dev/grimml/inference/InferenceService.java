package dev.grimml.inference;

import dev.grimml.buffer.CombatSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

// alert only: scores finished windows on grimml-inference, never touches the packet or main thread
public final class InferenceService {

    public record Job(long anon, long session, long epochMs, long durationMs, boolean testAccount, double[] features) {}

    private final ArrayBlockingQueue<Job> queue;
    private final ViolationBuffer violations;
    private final AlertSink alerts;
    private final Logger log;
    private final int minFrames;
    private final Thread worker;
    private volatile ModelBundle bundle;
    private volatile boolean running = true;

    public final AtomicLong scored = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    public final AtomicLong alertCount = new AtomicLong();
    private volatile long lastNanos;
    private volatile long maxNanos;
    private volatile double emaNanos;

    public interface AlertSink {
        void alert(Job job, double proba, ViolationBuffer.State state);
    }

    public InferenceService(int queueCapacity, ViolationBuffer violations, AlertSink alerts, int minFrames, Logger log) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.violations = violations;
        this.alerts = alerts;
        this.minFrames = minFrames;
        this.log = log;
        this.worker = new Thread(this::loop, "grimml-inference");
        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.NORM_PRIORITY - 1);
        this.worker.start();
    }

    public boolean load(Path dir) {
        if (!Files.isRegularFile(dir.resolve("model.txt")) || !Files.isRegularFile(dir.resolve("model_meta.json"))) {
            bundle = null;
            log.warning("inference enabled but " + dir + " has no model.txt + model_meta.json; scoring off");
            return false;
        }
        try {
            bundle = ModelBundle.load(dir);
            log.info("inference model loaded from " + dir + " threshold " + bundle.threshold);
            return true;
        } catch (Exception e) {
            bundle = null;
            log.warning("inference model load failed: " + e.getMessage());
            return false;
        }
    }

    public boolean loaded() {
        return bundle != null;
    }

    public ViolationBuffer violations() {
        return violations;
    }

    // writer thread: features are cheap primitives math on the pooled snapshot before it is returned
    public void offer(CombatSnapshot snap) {
        if (bundle == null || snap.frameCount < minFrames) {
            return;
        }
        long dur = Math.max(0L, (snap.windowEndNanos - snap.windowStartNanos) / 1_000_000L);
        Job job = new Job(snap.anonPlayerId, snap.sessionId, snap.windowStartEpochMs, dur, snap.testAccount,
                FeatureExtractor.extract(snap));
        if (!queue.offer(job)) {
            dropped.incrementAndGet();
        }
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }

    public String stats() {
        return String.format(java.util.Locale.ROOT,
                "model %s scored %d dropped %d errors %d alerts %d queue %d latency last %.2fms avg %.2fms max %.2fms",
                bundle == null ? "off" : "on", scored.get(), dropped.get(), errors.get(), alertCount.get(), queue.size(),
                lastNanos / 1e6, emaNanos / 1e6, maxNanos / 1e6);
    }

    private void loop() {
        while (running) {
            Job job;
            try {
                job = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            ModelBundle b = bundle;
            if (job == null || b == null) {
                continue;
            }
            long t0 = System.nanoTime();
            try {
                double p = b.score(job.features());
                long dt = System.nanoTime() - t0;
                lastNanos = dt;
                maxNanos = Math.max(maxNanos, dt);
                emaNanos = emaNanos == 0.0 ? dt : emaNanos * 0.98 + dt * 0.02;
                scored.incrementAndGet();
                if (violations.add(job.anon(), p, t0)) {
                    alertCount.incrementAndGet();
                    alerts.alert(job, p, violations.get(job.anon()));
                }
            } catch (RuntimeException e) {
                errors.incrementAndGet();
            }
        }
    }
}
