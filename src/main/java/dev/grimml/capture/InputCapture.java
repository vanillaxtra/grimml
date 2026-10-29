package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import dev.grimml.buffer.PlayerRingBuffer;

public final class InputCapture {

    public void onInput(PacketReceiveEvent event, PlayerRingBuffer buf) {
        var pkt = new WrapperPlayClientPlayerInput(event);
        buf.applyInput(pkt.isForward(), pkt.isLeft(), pkt.isBackward(), pkt.isRight(),
                pkt.isJump(), pkt.isShift(), pkt.isSprint());
    }
}
