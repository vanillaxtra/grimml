package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.format.RecordTypes;

// attack packets open the combat window that the sampler later flushes
public final class AttackCapture {

    private final GrimMLPlugin plugin;

    public AttackCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onInteract(PacketReceiveEvent event, PlayerRingBuffer buf, int tick, long nanos) {
        var pkt = new WrapperPlayClientInteractEntity(event);
        if (pkt.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            int targetId = pkt.getEntityId();
            buf.writeEvent(RecordTypes.ATTACK, tick, nanos, targetId, 0, 0, buf.pingMs);
            long postNanos = plugin.config().postCombatSeconds() * 1_000_000_000L;
            buf.markAttack(nanos, postNanos);
        }
    }
}
