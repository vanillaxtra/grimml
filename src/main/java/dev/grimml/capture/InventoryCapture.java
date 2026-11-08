package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.InventoryMirror;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.EnchantPacker;
import dev.grimml.item.ItemCategories;
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
        var item = pkt.getItem();
        buf.inv.setSlot(pkt.getSlot(), ItemUtil.nameId(item), ItemUtil.category(item), EnchantPacker.pack(item));
        if (pkt.getSlot() == InventoryMirror.OFFHAND && buf.inv.offCat() == ItemCategories.TOTEM) {
            buf.emitRetotem(RecordTypes.RETOTEM_UNKNOWN, System.nanoTime());
        }
    }

    public void onWindowItems(PacketSendEvent event, PlayerRingBuffer buf) {
        var pkt = new WrapperPlayServerWindowItems(event);
        if (pkt.getWindowId() != 0) {
            return;
        }
        var items = pkt.getItems();
        int n = Math.min(items.size(), InventoryMirror.SLOTS);
        for (int i = 0; i < n; i++) {
            var it = items.get(i);
            buf.inv.setSlot(i, ItemUtil.nameId(it), ItemUtil.category(it), EnchantPacker.pack(it));
        }
    }

    public void onOpen(PacketSendEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayServerOpenWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.WINDOW, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getContainerId(), 1, buf.lastAckTx, 0, 0, 0);
        if (buf.totemPopNanos != 0L) {
            buf.windowSincePop = true;
        }
    }

    public void onClick(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientClickWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int mode = pkt.getWindowClickType() == null ? 0 : pkt.getWindowClickType().ordinal();
        var carried = pkt.getCarriedItemStack();
        buf.writeEvent(RecordTypes.CLICK, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getWindowId(), pkt.getSlot() | (pkt.getButton() << 16), mode,
                ItemUtil.category(carried), ItemUtil.nameId(carried), 0);
        if (pkt.getWindowId() == 0) {
            applyChangedSlots(pkt, buf);
        }
        if (buf.totemPopNanos != 0L) {
            buf.clicksSincePop++;
            if (buf.inv.offCat() == ItemCategories.TOTEM) {
                buf.emitRetotem(RecordTypes.RETOTEM_CLICK, nanos);
            }
        }
        if (buf.isCombatActive(nanos) || buf.lastAttackNanos != 0L) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_CLICK);
        }
    }

    // window 0 clicks are client predicted so the server rarely echoes set-slot
    private static void applyChangedSlots(WrapperPlayClientClickWindow pkt, PlayerRingBuffer buf) {
        try {
            var hashed = pkt.getHashedSlots();
            if (hashed != null && !hashed.isEmpty()) {
                for (var e : hashed.entrySet()) {
                    String key = e.getValue().map(h -> h.getItem().getName().getKey()).orElse("air");
                    setFromKey(buf, e.getKey(), key);
                }
                return;
            }
            var slots = pkt.getSlots();
            if (slots.isPresent()) {
                for (var e : slots.get().entrySet()) {
                    var it = e.getValue();
                    buf.inv.setSlot(e.getKey(), ItemUtil.nameId(it), ItemUtil.category(it), EnchantPacker.pack(it));
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void setFromKey(PlayerRingBuffer buf, int slot, String key) {
        int id = ItemCategories.nameId(key);
        int ench = buf.inv.idAt(slot) == id ? buf.inv.enchAt(slot) : 0;
        buf.inv.setSlot(slot, "air".equals(key) ? 0 : id, ItemCategories.of(key), ench);
    }

    public void onClose(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientCloseWindow(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.WINDOW, tick, clock.nsOffset(nanos), ord, nanos,
                pkt.getWindowId(), 2, 0, 0, 0, 0);
    }
}
