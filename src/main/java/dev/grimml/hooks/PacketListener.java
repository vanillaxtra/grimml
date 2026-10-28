package dev.grimml.hooks;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import dev.grimml.GrimMLPlugin;
import dev.grimml.record.PlayerRingBuffer;
import dev.grimml.record.RecordTypes;
import dev.grimml.record.SessionRegistry;
import org.bukkit.entity.Player;

// reads only movement/attack packets on the netty thread
// zero allocation on hot path no entity lookup for hit offset in phase 1
public final class PacketListener extends PacketListenerAbstract {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;

    public PacketListener(GrimMLPlugin plugin, SessionRegistry registry) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!plugin.recordingEnabled()) {
            return;
        }
        Object p = event.getPlayer();
        if (!(p instanceof Player player)) {
            return;
        }
        PlayerRingBuffer buf = registry.get(player.getUniqueId());
        if (buf == null) {
            return;
        }

        // pack nanos once per packet; monotonic enough for windows
        long nanos = System.nanoTime();
        int tick = (int) (nanos / 50_000_000L);

        var type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            var pkt = new WrapperPlayClientPlayerRotation(event);
            buf.writeRotation(tick, pkt.getYaw(), pkt.getPitch(), nanos);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            var pkt = new WrapperPlayClientPlayerPositionAndRotation(event);
            buf.writeRotation(tick, pkt.getYaw(), pkt.getPitch(), nanos);
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            var pkt = new WrapperPlayClientInteractEntity(event);
            if (pkt.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                int targetId = pkt.getEntityId();
                buf.writeEvent(RecordTypes.ATTACK, tick, nanos, targetId, 0, 0, buf.pingMs);
                long postNanos = plugin.config().postCombatSeconds() * 1_000_000_000L;
                buf.markAttack(nanos, postNanos);
            }
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // nop in phase 1
    }
}
