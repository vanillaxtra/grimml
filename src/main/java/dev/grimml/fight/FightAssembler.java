package dev.grimml.fight;

import dev.grimml.buffer.CombatSnapshot;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.clock.TickClock;
import dev.grimml.config.PluginConfig;
import dev.grimml.format.RecordTypes;
import dev.grimml.writer.RecordWriter;

import java.util.concurrent.ThreadLocalRandom;

// writer thread only seqlock-copies rings and never blocks the packet path
public final class FightAssembler {

    private final SessionRegistry registry;
    private final PluginConfig config;
    private final TickClock clock;
    private final RecordWriter writer;
    private final PlayerRingBuffer[] nearby = new PlayerRingBuffer[8];
    private final long[] nearbyDist = new long[8];

    public FightAssembler(SessionRegistry registry, PluginConfig config, TickClock clock, RecordWriter writer) {
        this.registry = registry;
        this.config = config;
        this.clock = clock;
        this.writer = writer;
    }

    public void assemble() {
        long now = System.nanoTime();
        long pre = config.preCombatSeconds() * 1_000_000_000L;
        long range = (long) config.bystanderRange() * 1000L;
        long rangeSq = range * range;
        int maxBy = Math.min(config.bystanderMax(), nearby.length);

        for (PlayerRingBuffer attacker : registry.allBuffers()) {
            if (!attacker.isCombatActive(now) || attacker.lastAttackNanos == 0L) {
                continue;
            }
            if (now > attacker.combatActiveUntilNanos && now > attacker.lastAttackNanos + pre) {
                continue;
            }
            long fightId = attacker.fightId;
            if (fightId == 0L) {
                fightId = ThreadLocalRandom.current().nextLong() ^ now;
                attacker.fightId = fightId;
            }
            PlayerRingBuffer defender = attacker.lastTargetEntityId == 0
                    ? null
                    : registry.byEntityId(attacker.lastTargetEntityId);
            if (defender != null) {
                defender.fightId = fightId;
            }

            int n = 0;
            for (PlayerRingBuffer other : registry.allBuffers()) {
                if (other == attacker || other == defender) {
                    continue;
                }
                long d = attacker.distSqTo(other);
                if (d > rangeSq) {
                    continue;
                }
                if (n < maxBy) {
                    nearby[n] = other;
                    nearbyDist[n] = d;
                    n++;
                } else {
                    int worst = 0;
                    for (int i = 1; i < maxBy; i++) {
                        if (nearbyDist[i] > nearbyDist[worst]) {
                            worst = i;
                        }
                    }
                    if (d < nearbyDist[worst]) {
                        nearby[worst] = other;
                        nearbyDist[worst] = d;
                    }
                }
            }

            offer(attacker, RecordTypes.ROLE_ATTACKER, fightId, now, pre);
            if (defender != null) {
                offer(defender, RecordTypes.ROLE_DEFENDER, fightId, now, pre);
            }
            for (int i = 0; i < n; i++) {
                nearby[i].fightId = fightId;
                offer(nearby[i], RecordTypes.ROLE_BYSTANDER, fightId, now, pre);
                nearby[i] = null;
            }
        }
    }

    private void offer(PlayerRingBuffer buf, byte role, long fightId, long now, long pre) {
        CombatSnapshot snap = writer.borrow();
        long windowStart = Math.max(0L, buf.lastAttackNanos - pre);
        snap.reset();
        if (!snap.copyFrom(buf, windowStart, now)) {
            writer.giveBack(snap);
            return;
        }
        snap.fightId = fightId;
        snap.windowId = ThreadLocalRandom.current().nextLong() ^ now ^ buf.anonPlayerId;
        snap.role = role;
        snap.trigger = buf.triggerReason;
        snap.validity = buf.validity;
        snap.sessionId = buf.sessionId;
        snap.anonPlayerId = buf.anonPlayerId;
        snap.entityId = buf.entityId;
        snap.stateFlags = buf.stateFlags
                | (buf.bedrock ? RecordTypes.FLAG_BEDROCK : 0)
                | (buf.staff ? RecordTypes.FLAG_STAFF : 0)
                | (buf.testAccount ? RecordTypes.FLAG_TEST_ACCOUNT : 0)
                | (buf.mainHand == 1 ? RecordTypes.FLAG_MAIN_HAND_LEFT : 0)
                | buf.liveFlags;
        snap.pingMs = buf.pingMs;
        snap.jitterMs = buf.jitterMs;
        snap.clientBrand = buf.clientBrand;
        snap.clientProtocol = buf.clientProtocol;
        snap.windowStartNanos = windowStart;
        snap.windowEndNanos = now;
        snap.windowStartEpochMs = clock.epochMsForNanos(windowStart);
        snap.bootId = clock.bootId;
        snap.tickStart = clock.tick();
        snap.tickEnd = clock.tick();
        snap.msptStartCenti = (int) (clock.mspt() * 100);
        snap.msptEndCenti = snap.msptStartCenti;
        snap.tpsCenti = (int) (clock.tps() * 100);
        snap.testAccount = buf.testAccount;
        var tax = writer.plugin().taxonomy();
        snap.taxonomyVersion = tax.version;
        snap.taxonomyHash = tax.hash;
        snap.mainHand = buf.mainHand;
        snap.gamemode = buf.gamemode;
        snap.healthMilli = buf.healthMilli;
        snap.absorptionMilli = buf.absorptionMilli;
        snap.food = buf.food;
        snap.totemCount = buf.inv.totemCount();
        snap.hotbarTotem = buf.inv.hotbarHasTotem();
        snap.attrDamage = buf.attrDamage;
        snap.attrSpeed = buf.attrSpeed;
        snap.attrRange = buf.attrRange;
        snap.attrMove = buf.attrMove;
        snap.attrJump = buf.attrJump;
        snap.attrScale = buf.attrScale;
        snap.attrKbRes = buf.attrKbRes;
        snap.attrGravity = buf.attrGravity;
        snap.effectsPacked0 = buf.effectsPacked0;
        snap.effectsPacked1 = buf.effectsPacked1;
        snap.serverId = config.serverId();
        snap.kit = config.kit();
        snap.mapId = config.mapId();
        snap.relativePositions = config.relativePositions();
        snap.mcVersion = writer.mcVersion();
        snap.grimVersion = writer.grimVersion();
        snap.peVersion = writer.peVersion();
        snap.world = writer.worldName();
        if (clock.lastDurationMs() > 60) {
            snap.validity |= RecordTypes.VALID_LAG;
        }
        writer.offerAssembled(snap);
    }
}
