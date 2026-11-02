package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;

public final class AttackCapture {

    private static final int HIT_NONE = packHit(0, 0, 0);

    private final GrimMLPlugin plugin;

    public AttackCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onInteract(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientInteractEntity(event);
        if (pkt.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }
        record(buf, clock, nanos, pkt.getEntityId(), pkt.isSneaking().orElse(false));
    }

    public void onAttack(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientAttack(event);
        record(buf, clock, nanos, pkt.getEntityId(), (buf.liveFlags & RecordTypes.FLAG_SNEAK) != 0);
    }

    private void record(PlayerRingBuffer buf, TickClock clock, long nanos, int targetId, boolean sneaking) {
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        long targetAnon = plugin.registry().entities.anonOf(targetId);
        int sneak = sneaking ? 1 : 0;
        buf.lastTargetEntityId = targetId;
        buf.lastTargetAnonId = targetAnon;
        buf.writeEvent(RecordTypes.ATTACK, tick, clock.nsOffset(nanos), ord, nanos,
                targetAnon, targetId, (sneak << 1) | (buf.inv.heldCat() << 8), HIT_NONE, buf.inv.heldId(), buf.lastAckTx);
        long post = plugin.config().postCombatSeconds() * 1_000_000_000L;
        byte trigger = RecordTypes.TRIGGER_ATTACK;
        if (buf.inv.heldCat() == ItemCategories.MACE && buf.fallMilli > 2000) {
            trigger = RecordTypes.TRIGGER_MACE;
        }
        buf.markCombat(nanos, post, trigger);
    }

    private static int packHit(float x, float y, float z) {
        int ix = clampMilli(x);
        int iy = clampMilli(y);
        int iz = clampMilli(z);
        return (ix & 0x3FF) | ((iy & 0x3FF) << 10) | ((iz & 0x3FF) << 20);
    }

    private static int clampMilli(float v) {
        int m = (int) (v * 1000);
        if (m < -512) return 0;
        if (m > 511) return 1023;
        return m + 512;
    }
}
