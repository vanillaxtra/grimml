package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;

public final class OutgoingCapture {

    private final GrimMLPlugin plugin;

    public OutgoingCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onVelocity(PacketSendEvent event, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerEntityVelocity(event);
        PlayerRingBuffer buf = plugin.registry().byEntityId(pkt.getEntityId());
        if (buf == null) {
            return;
        }
        var v = pkt.getVelocity();
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.KNOCKBACK, tick, clock.nsOffset(nanos), ord, nanos,
                (int) Math.round(v.x * 8000), (int) Math.round(v.y * 8000), (int) Math.round(v.z * 8000),
                buf.lastAckTx, buf.liveFlags, 0);
        if (buf.jumpHeld()) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_KNOCKBACK_JUMP);
        }
    }

    public void onStatus(PacketSendEvent event, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerEntityStatus(event);
        if (pkt.getStatus() != 35) {
            return;
        }
        PlayerRingBuffer buf = plugin.registry().byEntityId(pkt.getEntityId());
        if (buf == null) {
            return;
        }
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int fromOff = buf.inv.offCat() == dev.grimml.item.ItemCategories.TOTEM ? 1 : 0;
        buf.writeEvent(RecordTypes.TOTEM, tick, clock.nsOffset(nanos), ord, nanos,
                buf.healthMilli, fromOff, buf.lastAckTx, buf.inv.totemCount(), buf.inv.hotbarHasTotem() ? 1 : 0, 0);
        buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_TOTEM);
    }

    public void onTeleport(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerPlayerPositionAndLook(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.TELEPORT, tick, clock.nsOffset(nanos), ord, nanos,
                (int) Math.round(pkt.getX() * 1000), (int) Math.round(pkt.getY() * 1000), (int) Math.round(pkt.getZ() * 1000),
                0, 0, 0);
        buf.orValidity(RecordTypes.VALID_TELEPORT);
    }
}
