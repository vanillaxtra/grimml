package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
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

    // an entity's velocity goes to every player who can see it. only the copy sent to that player's own client
    // is their knockback; recording the others wrote one duplicate event per viewer, from the viewers' threads
    public void onVelocity(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerEntityVelocity(event);
        if (pkt.getEntityId() != buf.entityId) {
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

    // same rule as velocity: the totem pop is recorded once, from the copy sent to the player who popped
    public void onStatus(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerEntityStatus(event);
        if (pkt.getStatus() != 35 || pkt.getEntityId() != buf.entityId) {
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

    // the server can move the selected hotbar slot itself; without this the mirror kept the old slot
    public void onHeldSlot(PacketSendEvent event, PlayerRingBuffer buf) {
        buf.inv.setHeld(new WrapperPlayServerHeldItemChange(event).getSlot());
    }
}
