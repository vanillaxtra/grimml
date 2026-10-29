package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import dev.grimml.buffer.PlayerRingBuffer;

public final class RotationCapture {

    public void onRotation(PacketReceiveEvent event, PlayerRingBuffer buf, int tick, long nanos) {
        var pkt = new WrapperPlayClientPlayerRotation(event);
        buf.writeRotation(tick, pkt.getYaw(), pkt.getPitch(), nanos);
    }

    public void onPositionAndRotation(PacketReceiveEvent event, PlayerRingBuffer buf, int tick, long nanos) {
        var pkt = new WrapperPlayClientPlayerPositionAndRotation(event);
        buf.writeRotation(tick, pkt.getYaw(), pkt.getPitch(), nanos);
    }
}
