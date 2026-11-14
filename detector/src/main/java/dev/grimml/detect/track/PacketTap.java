package dev.grimml.detect.track;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Netty-thread capture. Each packet costs a map lookup and a few int writes under the player's own lock;
 * nothing here touches Bukkit or allocates per packet beyond the PacketEvents wrapper.
 * The set of packets that open a frame tick mirrors the recorder's captures (see PlayerTrack).
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
        var type = event.getPacketType();
        PlayerTrack t = track(event.getUser().getUUID());
        if (t == null) {
            return;
        }
        int now = tick.getAsInt();
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            var p = new WrapperPlayClientPlayerFlying(event);
            var loc = p.getLocation();
            t.flying(now, loc.getX(), loc.getZ(), loc.getYaw(), loc.getPitch(), p.isOnGround(),
                    p.hasPositionChanged(), p.hasRotationChanged());
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            t.clientTickEnd();
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            var p = new WrapperPlayClientInteractEntity(event);
            if (p.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                t.attack(now, System.nanoTime());
            }
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            var a = new WrapperPlayClientEntityAction(event).getAction();
            if (a != null && (a.name().contains("ELYTRA") || a.name().contains("FLYING_WITH"))) {
                t.packet(now);
            }
        } else if (type == PacketType.Play.Client.ANIMATION
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.PLAYER_DIGGING
                || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CLOSE_WINDOW
                || type == PacketType.Play.Client.PONG
                || type == PacketType.Play.Client.KEEP_ALIVE) {
            t.packet(now);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
            return;
        }
        switch (type) {
            case ENTITY_VELOCITY, ENTITY_STATUS, PLAYER_POSITION_AND_LOOK, OPEN_WINDOW -> {
            }
            default -> {
                return;
            }
        }
        PlayerTrack t = track(event.getUser().getUUID());
        if (t == null) {
            return;
        }
        switch (type) {
            case ENTITY_VELOCITY -> {
                if (new WrapperPlayServerEntityVelocity(event).getEntityId() == t.entityId) {
                    t.packet(tick.getAsInt());
                }
            }
            case ENTITY_STATUS -> {
                var p = new WrapperPlayServerEntityStatus(event);
                if (p.getStatus() == 35 && p.getEntityId() == t.entityId) {
                    t.packet(tick.getAsInt());
                }
            }
            default -> t.packet(tick.getAsInt());
        }
    }
}
