package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;

public final class InputCapture {

    public void onInput(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientPlayerInput(event);
        buf.applyInput(pkt.isForward(), pkt.isLeft(), pkt.isBackward(), pkt.isRight(),
                pkt.isJump(), pkt.isShift(), pkt.isSprint());
        int bits = (pkt.isForward() ? 1 : 0) | (pkt.isLeft() ? 2 : 0) | (pkt.isBackward() ? 4 : 0) | (pkt.isRight() ? 8 : 0)
                | (pkt.isJump() ? 16 : 0) | (pkt.isShift() ? 32 : 0) | (pkt.isSprint() ? 64 : 0);
        int tick = clock.tick();
        buf.writeEvent(RecordTypes.INPUT, tick, clock.nsOffset(nanos), buf.peekOrder(), nanos, bits, 0, 0, 0, 0, 0);
    }
}
