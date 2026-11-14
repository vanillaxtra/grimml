package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemUtil;

public final class InventoryCapture {

    private final GrimMLPlugin plugin;

    public InventoryCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onSetSlot(PacketSendEvent event, PlayerRingBuffer buf) {
        var pkt = new WrapperPlayServerSetSlot(event);
        if (pkt.getWindowId() != 0) {
            return;
        }
        buf.inv.setSlot(pkt.getSlot(), ItemUtil.nameId(pkt.getItem()), ItemUtil.category(pkt.getItem()), 0);
    }

    public void onWindowItems(PacketSendEvent event, PlayerRingBuffer buf) {
        var pkt = new WrapperPlayServerWindowItems(event);
        if (pkt.getWindowId() != 0) {
            return;
        }
        var items = pkt.getItems();
        int n = Math.min(items.size(), 46);
        for (int i = 0; i < n; i++) {
            var it = items.get(i);
            buf.inv.setSlot(i, ItemUtil.nameId(it), ItemUtil.category(it), 0);
        }
    }

    public void onOpen(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerOpenWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.WINDOW, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getContainerId(), 1, buf.lastAckTx, 0, 0, 0);
    }

    public void onClick(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientClickWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int mode = pkt.getWindowClickType() == null ? 0 : pkt.getWindowClickType().ordinal();
        buf.writeEvent(RecordTypes.CLICK, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getWindowId(), pkt.getSlot() | (pkt.getButton() << 16), mode,
                ItemUtil.category(pkt.getCarriedItemStack()), ItemUtil.nameId(pkt.getCarriedItemStack()), 0);
        if (buf.isCombatActive(nanos) || buf.lastAttackNanos != 0L) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_CLICK);
        }
    }

    public void onClose(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientCloseWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.WINDOW, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getWindowId(), 2, 0, 0, 0, 0);
    }
}
