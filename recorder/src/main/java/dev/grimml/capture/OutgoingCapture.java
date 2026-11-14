package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
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

    // ---- schema 4 (docs/schema4.md). None of these open a frame tick, so schema 3 frames are unchanged.

    /** Movement of the current opponent as sent to this client: what the client was shown, and when (backtrack). */
    public void onEntityMove(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos, PacketType.Play.Server type) {
        int opp = buf.opponentEntityId;
        if (opp == 0) {
            return;
        }
        int id;
        int kind;
        int c, d, e;
        int yaw = 0;
        switch (type) {
            case ENTITY_RELATIVE_MOVE -> {
                var p = new WrapperPlayServerEntityRelativeMove(event);
                id = p.getEntityId();
                if (id != opp) return;
                kind = 1;
                c = milli(p.getDeltaX());
                d = milli(p.getDeltaY());
                e = milli(p.getDeltaZ());
            }
            case ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
                var p = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                id = p.getEntityId();
                if (id != opp) return;
                kind = 2;
                c = milli(p.getDeltaX());
                d = milli(p.getDeltaY());
                e = milli(p.getDeltaZ());
                yaw = Math.round(p.getYaw() * 100);
            }
            case ENTITY_TELEPORT -> {
                var p = new WrapperPlayServerEntityTeleport(event);
                id = p.getEntityId();
                if (id != opp) return;
                kind = 3;
                var pos = p.getPosition();
                c = milli(pos.getX()) - buf.xMilli;
                d = milli(pos.getY()) - buf.yMilli;
                e = milli(pos.getZ()) - buf.zMilli;
                yaw = Math.round(p.getValues().getYaw() * 100);
            }
            case ENTITY_POSITION_SYNC -> {
                var p = new WrapperPlayServerEntityPositionSync(event);
                id = p.getId();
                if (id != opp) return;
                kind = 4;
                var pos = p.getValues().getPosition();
                c = milli(pos.getX()) - buf.xMilli;
                d = milli(pos.getY()) - buf.yMilli;
                e = milli(pos.getZ()) - buf.zMilli;
                yaw = Math.round(p.getValues().getYaw() * 100);
            }
            default -> {
                return;
            }
        }
        int tick = clock.tick();
        buf.writeEventX(RecordTypes.ENTITY_SENT, tick, clock.nsOffset(nanos), buf.peekOrder(), nanos,
                id, kind, c, d, e, 1, yaw, 0, 0, 0);
    }

    /** End crystals, wind charges and TNT spawning near this player (crystal/anchor macros). */
    public void onSpawn(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var p = new WrapperPlayServerSpawnEntity(event);
        var t = p.getEntityType();
        int kind;
        if (t == EntityTypes.END_CRYSTAL) kind = 1;
        else if (t == EntityTypes.WIND_CHARGE) kind = 2;
        else if (t == EntityTypes.TNT) kind = 3;
        else if (t == EntityTypes.TNT_MINECART) kind = 4;
        else return;
        var pos = p.getPosition();
        int dx = milli(pos.getX()) - buf.xMilli;
        int dy = milli(pos.getY()) - buf.yMilli;
        int dz = milli(pos.getZ()) - buf.zMilli;
        if (Math.abs((long) dx) > 16_000 || Math.abs((long) dy) > 16_000 || Math.abs((long) dz) > 16_000) {
            return;
        }
        if (kind == 1) {
            buf.noteCrystal(p.getEntityId());
        }
        int tick = clock.tick();
        buf.writeEvent(RecordTypes.ENTITY_SPAWN, tick, clock.nsOffset(nanos), buf.peekOrder(), nanos,
                p.getEntityId(), kind, dx, dy, dz, 0);
    }

    /** Send time of every transaction and keep-alive; the reply is the existing rtt event with the same id. */
    public void onRttSent(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos, PacketType.Play.Server type) {
        long id;
        int kind;
        if (type == PacketType.Play.Server.PING) {
            id = new WrapperPlayServerPing(event).getId();
            kind = 1;
        } else {
            id = new WrapperPlayServerKeepAlive(event).getId();
            kind = 2;
        }
        int tick = clock.tick();
        buf.writeEvent(RecordTypes.RTT_SENT, tick, clock.nsOffset(nanos), buf.peekOrder(), nanos, id, kind, 0, 0, 0, 0);
    }

    private static int milli(double v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(v * 1000.0)));
    }
}
