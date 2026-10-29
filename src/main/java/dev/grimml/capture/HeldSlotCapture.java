package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;

public final class HeldSlotCapture {

    private final GrimMLPlugin plugin;

    public HeldSlotCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onHeld(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientHeldItemChange(event);
        int to = pkt.getSlot();
        int from = buf.inv.held();
        byte fromCat = buf.inv.heldCat();
        int fromId = buf.inv.heldId();
        buf.inv.setHeld(to);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int scroll = Math.abs(to - from) == 1 || (from == 0 && to == 8) || (from == 8 && to == 0) ? 1 : 0;
        buf.writeEvent(RecordTypes.HELD_SLOT, tick, clock.nsOffset(nanos), ord, nanos,
                from, to | (scroll << 8), fromCat | (buf.inv.heldCat() << 8), fromId, buf.inv.heldId(), 0);
        if (buf.isCombatActive(nanos) && Math.abs(to - from) > 0) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_SWAP);
        }
        if (fromCat == ItemCategories.AXE && buf.inv.heldCat() == ItemCategories.MACE) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_MACE);
        }
    }
}
