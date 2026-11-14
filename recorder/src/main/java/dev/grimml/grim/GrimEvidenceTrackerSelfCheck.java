package dev.grimml.grim;

import dev.grimml.clock.TickClock;
import dev.grimml.format.BinaryFormat;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GrimEvidenceTrackerSelfCheck {

    public static void main(String[] args) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.set("checks.Reach.weight", 3.0);
        y.set("checks.Reach.category", "combat");
        y.set("checks.Reach.trust", 1.0);
        y.set("checks.Hitboxes.weight", 2.0);
        y.set("checks.Hitboxes.category", "combat");
        y.set("checks.Hitboxes.trust", 1.0);
        y.set("score.promote-threshold", 4.0);
        y.set("score.promote-distinct-checks", 2);
        GrimWeights w = GrimWeightsLoader.parse(y);
        Path dir = Files.createTempDirectory("grimml-ev");
        GrimCheckCatalog cat = new GrimCheckCatalog(dir.resolve("checks.json"));
        cat.learn("Reach");
        cat.learn("Hitboxes");
        EvidenceStore store = new EvidenceStore(dir.resolve("labels.csv"), dir.resolve("review.csv"));
        FlagQueue q = new FlagQueue(8);
        GrimEvidenceTracker t = new GrimEvidenceTracker(q, new TickClock(), null, cat, store, null, w, true);
        FlagQueue.Slot s = new FlagQueue.Slot();
        s.anonId = 1;
        s.sessionId = 2;
        s.nanos = System.nanoTime();
        s.checkHash = BinaryFormat.hashCheckName("Reach");
        s.ping = 30;
        t.accept(s, w, 10);
        s.checkHash = BinaryFormat.hashCheckName("Hitboxes");
        s.nanos += 1_000_000L;
        t.accept(s, w, 10);
        if (t.top(1).isEmpty() || t.top(1).get(0).score < 4) {
            throw new AssertionError("score");
        }
        if (!t.top(1).get(0).promoted) {
            throw new AssertionError("promoted");
        }
        String next = store.nextPending();
        if (next == null || !next.contains("GRIM_WEAK")) {
            throw new AssertionError("review " + next);
        }
        System.out.println("grim evidence tracker self-check passed");
    }
}
