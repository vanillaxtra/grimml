package dev.grimml.grim;

import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.writer.RecordWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GrimEvidenceTracker {

    private final FlagQueue queue;
    private final ConcurrentHashMap<Long, PlayerEvidence> players = new ConcurrentHashMap<>();
    private final FlagQueue.Slot tmp = new FlagQueue.Slot();
    private final TickClock clock;
    private final SessionRegistry registry;
    private final GrimCheckCatalog catalog;
    private final EvidenceStore store;
    private final RecordWriter writer;
    private volatile GrimWeights weights;
    private volatile boolean enabled;
    private ScheduledExecutorService tick;

    public GrimEvidenceTracker(
            FlagQueue queue,
            TickClock clock,
            SessionRegistry registry,
            GrimCheckCatalog catalog,
            EvidenceStore store,
            RecordWriter writer,
            GrimWeights weights,
            boolean enabled
    ) {
        this.queue = queue;
        this.clock = clock;
        this.registry = registry;
        this.catalog = catalog;
        this.store = store;
        this.writer = writer;
        this.weights = weights;
        this.enabled = enabled;
    }

    public void start() {
        if (tick != null) {
            return;
        }
        tick = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "grimml-evidence");
            t.setDaemon(true);
            return t;
        });
        tick.scheduleAtFixedRate(this::cycle, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (tick != null) {
            tick.shutdownNow();
            tick = null;
        }
        cycle();
    }

    public void setWeights(GrimWeights w) {
        if (w != null) {
            weights = w;
        }
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    public FlagQueue queue() {
        return queue;
    }

    public void cycle() {
        GrimWeights w = weights;
        if (!enabled || w == null) {
            while (queue.poll(tmp)) {
            }
            return;
        }
        double mspt = clock.mspt();
        while (queue.poll(tmp)) {
            accept(tmp, w, mspt);
        }
    }

    void accept(FlagQueue.Slot s, GrimWeights w, double mspt) {
        if (LagFilter.skip(s, mspt, w)) {
            return;
        }
        String name = catalog == null ? null : catalog.nameOf(s.checkHash);
        CheckWeight cw = w.of(name == null ? "" : name);
        if (cw.weight <= 0) {
            return;
        }
        PlayerEvidence ev = players.computeIfAbsent(s.anonId, PlayerEvidence::new);
        ev.score = ScoreMath.decay(ev.score, s.nanos - ev.lastNanos, w.halfLifeSeconds);
        ev.score = ScoreMath.add(ev.score, cw);
        ev.note(s.checkHash, s.sessionId, s.nanos, w.promoteWindowSeconds * 1_000_000_000L);
        double conf = ScoreMath.confidence(ev.score, w.confidenceScale);
        int centi = (int) Math.round(conf * 100);
        if (cw.category.combatRelevant()) {
            stampWindow(s.anonId, s.checkHash, centi);
            long from = clock.epochMsForNanos(s.nanos - 5_000_000_000L);
            long to = clock.epochMsForNanos(s.nanos);
            long reviewId = store.writeWindow(s.anonId, s.sessionId, from, to, conf, ev.score, ev.summary());
            if (writer != null) {
                writer.enqueueSessionLine("grim-weak " + Long.toUnsignedString(s.anonId, 36)
                        + " " + Long.toUnsignedString(s.sessionId, 36)
                        + " " + reviewId + " " + centi + " " + Integer.toHexString(s.checkHash));
            }
        } else if (cw.category == CheckCategory.MOVEMENT) {
            stampSetback(s.anonId);
        }
        int distinct = ev.distinctInWindow(s.nanos, w.promoteWindowSeconds * 1_000_000_000L);
        int sessions = ev.sessionsFor(s.checkHash);
        if (!ev.promoted && Promotion.ready(ev.score, distinct, sessions, w)) {
            ev.promoted = true;
            store.writePlayer(s.anonId, s.sessionId, clock.epochMsForNanos(s.nanos), 0, conf, ev.score, ev.summary());
            if (writer != null) {
                writer.enqueueSessionLine("grim-player " + Long.toUnsignedString(s.anonId, 36)
                        + " " + Long.toUnsignedString(s.sessionId, 36) + " " + centi);
            }
        }
    }

    public void sessionEnd(long anonId, long sessionId) {
        PlayerEvidence ev = players.get(anonId);
        if (ev == null || writer == null) {
            return;
        }
        GrimWeights w = weights;
        ev.score = ScoreMath.decay(ev.score, System.nanoTime() - ev.lastNanos, w == null ? 1800 : w.halfLifeSeconds);
        store.writeSession(anonId, sessionId, 0, System.currentTimeMillis(), ev.score, ev.summary());
        writer.enqueueSessionLine("grim-summary " + Long.toUnsignedString(anonId, 36)
                + " " + Long.toUnsignedString(sessionId, 36) + " " + ScoreMath.confidence(ev.score, w == null ? 0.12 : w.confidenceScale)
                + " " + ev.summary());
    }

    public List<PlayerEvidence> top(int limit) {
        GrimWeights w = weights;
        long now = System.nanoTime();
        List<PlayerEvidence> list = new ArrayList<>(players.values());
        for (PlayerEvidence ev : list) {
            ev.score = ScoreMath.decay(ev.score, now - ev.lastNanos, w == null ? 1800 : w.halfLifeSeconds);
            ev.lastNanos = now;
        }
        list.sort(Comparator.comparingDouble((PlayerEvidence e) -> e.score).reversed());
        if (list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    public long drops() {
        return queue.drops();
    }

    private void stampWindow(long anonId, int checkHash, int centi) {
        if (registry == null) {
            return;
        }
        for (PlayerRingBuffer buf : registry.allBuffers()) {
            if (buf.anonPlayerId == anonId) {
                buf.stampGrimWeak(checkHash, centi);
                buf.markCombat(System.nanoTime(), 2_000_000_000L, RecordTypes.TRIGGER_FLAG);
                return;
            }
        }
    }

    private void stampSetback(long anonId) {
        if (registry == null) {
            return;
        }
        for (PlayerRingBuffer buf : registry.allBuffers()) {
            if (buf.anonPlayerId == anonId) {
                buf.orValidity(RecordTypes.VALID_SETBACK);
                return;
            }
        }
    }
}
