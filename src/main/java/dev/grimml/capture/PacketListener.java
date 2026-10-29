package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.buffer.SessionRegistry;
import org.bukkit.entity.Player;

// single packetevents listener on the netty thread dispatches to preallocated capture handlers
// zero allocation on hot path no entity lookup for hit offset in phase 1
public final class PacketListener extends PacketListenerAbstract {

    private final GrimMLPlugin plugin;
    private final SessionRegistry registry;
    private final RotationCapture rotation = new RotationCapture();
    private final AttackCapture attack;

    public PacketListener(GrimMLPlugin plugin, SessionRegistry registry) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.registry = registry;
        this.attack = new AttackCapture(plugin);
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
            rotation.onRotation(event, buf, tick, nanos);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            rotation.onPositionAndRotation(event, buf, tick, nanos);
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            attack.onInteract(event, buf, tick, nanos);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // nop in phase 1
    }
}
