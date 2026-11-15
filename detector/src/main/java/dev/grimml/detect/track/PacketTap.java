package dev.grimml.detect.track;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Netty-thread capture, mirroring the recorder's captures (PacketListener + *Capture classes): the same packets open
 * frame ticks, and the schema 4 events the features need are recorded the same way. Per packet: one map lookup, a
 * PacketEvents wrapper for the types we read, and a few writes under the player's own lock. No Bukkit calls.
 */
public final class PacketTap extends PacketListenerAbstract {

    private final Map<UUID, PlayerTrack> tracks;
    private final IntSupplier tick;

    public PacketTap(Map<UUID, PlayerTrack> tracks, IntSupplier tick) {
        super(PacketListenerPriority.LOWEST); // same as the recorder: see packets before anything cancels them
        this.tracks = tracks;
        this.tick = tick;
    }

    private PlayerTrack track(UUID id) {
        return id == null ? null : tracks.get(id);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PlayerTrack t = track(event.getUser().getUUID());
        if (t == null) {
            return;
        }
        long nanos = System.nanoTime();
        t.inbound(nanos);
        var type = event.getPacketType();
        int now = tick.getAsInt();
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            var p = new WrapperPlayClientPlayerFlying(event);
            var loc = p.getLocation();
            t.flying(now, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), p.isOnGround(),
                    p.hasPositionChanged(), p.hasRotationChanged());
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            t.clientTickEnd();
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            var p = new WrapperPlayClientPlayerInput(event);
            int bits = (p.isForward() ? 1 : 0) | (p.isLeft() ? 2 : 0) | (p.isBackward() ? 4 : 0) | (p.isRight() ? 8 : 0)
                    | (p.isJump() ? 16 : 0) | (p.isShift() ? 32 : 0) | (p.isSprint() ? 64 : 0);
            t.input(now, nanos, bits);
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            var p = new WrapperPlayClientInteractEntity(event);
            if (p.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                t.attack(now, nanos, p.getEntityId());
            }
        } else if (type == PacketType.Play.Client.ANIMATION) {
            t.swing(now, nanos);
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            t.heldChange(now, nanos, new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            t.useItem(now, new WrapperPlayClientUseItem(event).getHand() == InteractionHand.OFF_HAND);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            var a = new WrapperPlayClientPlayerDigging(event).getAction();
            String n = a == null ? "" : a.name();
            t.dig(now, nanos, n.contains("RELEASE"), n.contains("SWAP"), n.equals("STAB"));
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            var pos = new WrapperPlayClientPlayerBlockPlacement(event).getBlockPosition();
            t.place(now, nanos, ClickedBlock.category(t.grimUser, pos.getX(), pos.getY(), pos.getZ()));
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            entityAction(t, now, nanos, new WrapperPlayClientEntityAction(event).getAction());
        } else if (type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CLOSE_WINDOW
                || type == PacketType.Play.Client.PONG
                || type == PacketType.Play.Client.KEEP_ALIVE) {
            t.packet(now);
        }
    }

    // recorder ActionCapture.onEntityAction
    private static void entityAction(PlayerTrack t, int now, long nanos, WrapperPlayClientEntityAction.Action a) {
        if (a == null) {
            return;
        }
        String n = a.name();
        int code = 0;
        if (n.contains("SPRINTING") && n.contains("START")) code = 1;
        else if (n.contains("SPRINTING")) code = 2;
        if (n.contains("SNEAK") && n.contains("START")) code = 3;
        else if (n.contains("SNEAK")) code = 4;
        if (code != 0) {
            t.moveState(now, nanos, code);
        }
        if (n.contains("ELYTRA") || n.contains("FLYING_WITH")) {
            t.packet(now);
            t.setLive(PlayerTrack.L_GLIDING, true);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
            return;
        }
        switch (type) {
            case ENTITY_VELOCITY, ENTITY_STATUS, PLAYER_POSITION_AND_LOOK, OPEN_WINDOW, HELD_ITEM_CHANGE,
                 SPAWN_ENTITY, PING, KEEP_ALIVE -> {
            }
            default -> {
                return;
            }
        }
        PlayerTrack t = track(event.getUser().getUUID());
        if (t == null) {
            return;
        }
        int now = tick.getAsInt();
        long nanos = System.nanoTime();
        switch (type) {
            case ENTITY_VELOCITY -> {
                var p = new WrapperPlayServerEntityVelocity(event);
                if (p.getEntityId() == t.entityId) {
                    var v = p.getVelocity();
                    t.knockback(now, nanos, v.getX(), v.getY(), v.getZ());
                }
            }
            case ENTITY_STATUS -> {
                var p = new WrapperPlayServerEntityStatus(event);
                if (p.getStatus() == 35 && p.getEntityId() == t.entityId) {
                    t.packet(now);
                }
            }
            case HELD_ITEM_CHANGE -> t.serverHeld(new WrapperPlayServerHeldItemChange(event).getSlot());
            case SPAWN_ENTITY -> {
                var p = new WrapperPlayServerSpawnEntity(event);
                var et = p.getEntityType();
                int kind = et == EntityTypes.END_CRYSTAL ? 1 : et == EntityTypes.WIND_CHARGE ? 2
                        : et == EntityTypes.TNT ? 3 : et == EntityTypes.TNT_MINECART ? 4 : 0;
                if (kind != 0) {
                    var pos = p.getPosition();
                    t.spawn(now, nanos, p.getEntityId(), kind, pos.getX(), pos.getY(), pos.getZ());
                }
            }
            case PING -> t.rttSent(now, nanos, new WrapperPlayServerPing(event).getId(), 1);
            case KEEP_ALIVE -> t.rttSent(now, nanos, new WrapperPlayServerKeepAlive(event).getId(), 2);
            default -> t.packet(now); // PLAYER_POSITION_AND_LOOK, OPEN_WINDOW
        }
    }
}
