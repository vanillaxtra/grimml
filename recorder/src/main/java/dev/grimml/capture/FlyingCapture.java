package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;

public final class FlyingCapture {

    public void onFlying(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientPlayerFlying(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        var loc = pkt.getLocation();
        int x = (int) Math.round(loc.getX() * 1000);
        int y = (int) Math.round(loc.getY() * 1000);
        int z = (int) Math.round(loc.getZ() * 1000);
        int yaw = (int) Math.round(loc.getYaw() * 100);
        int pitch = (int) Math.round(loc.getPitch() * 100);
        buf.applyFlying(tick, clock.nsOffset(nanos), ord, x, y, z, yaw, pitch,
                pkt.isOnGround(), pkt.isHorizontalCollision(), pkt.hasPositionChanged(), pkt.hasRotationChanged());
    }
}
