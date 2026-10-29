package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.HumanoidArm;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.FrameRing;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import dev.grimml.format.RecordTypes;
import dev.grimml.item.ItemCategories;
import dev.grimml.item.ItemUtil;

public final class ActionCapture {

    private final GrimMLPlugin plugin;

    public ActionCapture(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    public void onSwing(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientAnimation(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int hand = pkt.getHand() == InteractionHand.OFF_HAND ? 1 : 0;
        buf.writeEvent(RecordTypes.SWING, tick, clock.nsOffset(nanos), ord, nanos, hand, 0, 0, 0, 0, 0);
    }

    public void onUseItem(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientUseItem(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        int hand = pkt.getHand() == InteractionHand.OFF_HAND ? 1 : 0;
        byte cat = hand == 1 ? buf.inv.offCat() : buf.inv.heldCat();
        buf.writeEvent(RecordTypes.USE_ITEM, tick, clock.nsOffset(nanos), ord, nanos, hand, cat, 1,
                (int) (pkt.getYaw() * 100), (int) (pkt.getPitch() * 100), 0);
        if (cat == ItemCategories.SHIELD) {
            buf.liveFlags |= RecordTypes.FLAG_BLOCKING;
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_SHIELD);
        }
        if (cat == ItemCategories.WIND_CHARGE) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_WIND);
        }
        if (hand == 0) {
            buf.liveFlags |= RecordTypes.FLAG_USING_MAIN;
        } else {
            buf.liveFlags |= RecordTypes.FLAG_USING_OFF;
        }
    }

    public void onDig(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientPlayerDigging(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        var action = pkt.getAction();
        int code = action == null ? 0 : action.ordinal();
        var pos = pkt.getBlockPosition();
        int relX = pos.getX() - buf.xMilli / 1000;
        int relY = pos.getY() - buf.yMilli / 1000;
        int relZ = pos.getZ() - buf.zMilli / 1000;
        buf.writeEvent(RecordTypes.BREAK, tick, clock.nsOffset(nanos), ord, nanos, code, relX, relY, relZ, pkt.getBlockFaceId(), 0);
        if (action != null && action.name().contains("RELEASE")) {
            buf.liveFlags &= ~(RecordTypes.FLAG_USING_MAIN | RecordTypes.FLAG_USING_OFF | RecordTypes.FLAG_BLOCKING);
            buf.writeEvent(RecordTypes.USE_ITEM, tick, clock.nsOffset(nanos), ord, nanos, 0, 0, 0, 0, 0, 0);
        }
        if (action != null && action.name().contains("SWAP")) {
            buf.writeEvent(RecordTypes.HELD_SLOT, tick, clock.nsOffset(nanos), ord, nanos, 40, 40, buf.inv.heldCat(), buf.inv.offCat(), 1, 0);
        }
        if (action != null && action.name().contains("DROP")) {
            buf.writeEvent(RecordTypes.CONSUMABLE, tick, clock.nsOffset(nanos), ord, nanos, 1, buf.inv.heldCat(), buf.inv.heldId(), 0, 0, 0);
        }
    }

    public void onPlace(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientPlayerBlockPlacement(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        var pos = pkt.getBlockPosition();
        int relX = pos.getX() - buf.xMilli / 1000;
        int relY = pos.getY() - buf.yMilli / 1000;
        int relZ = pos.getZ() - buf.zMilli / 1000;
        var cursor = pkt.getCursorPosition();
        int cursorPacked = ((int) (cursor.x * 15) & 0xF) | (((int) (cursor.y * 15) & 0xF) << 4) | (((int) (cursor.z * 15) & 0xF) << 8);
        byte cat = pkt.getItemStack().map(ItemUtil::category).orElse(buf.inv.heldCat());
        int inside = pkt.getInsideBlock().orElse(false) ? 1 : 0;
        buf.writeEvent(RecordTypes.BLOCK_PLACE, tick, clock.nsOffset(nanos), ord, nanos,
                relX, relY, relZ | (pkt.getFaceId() << 16), cursorPacked, cat | (inside << 8), buf.yawCenti);
        if (buf.isCombatActive(nanos) || buf.fallMilli > 1000) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_PLACE);
        }
        if (cat == ItemCategories.WATER_BUCKET || cat == ItemCategories.LAVA_BUCKET) {
            buf.writeEvent(RecordTypes.BUCKET, tick, clock.nsOffset(nanos), ord, nanos, cat, relX, relY, relZ, buf.fallMilli, 0);
        }
        if (cat == ItemCategories.COBWEB) {
            buf.markCombat(nanos, plugin.config().postCombatSeconds() * 1_000_000_000L, RecordTypes.TRIGGER_PLACE);
        }
    }

    public void onEntityAction(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientEntityAction(event);
        var a = pkt.getAction();
        if (a == null) {
            return;
        }
        String n = a.name();
        if (n.contains("SPRINTING") && n.contains("START")) {
            buf.liveFlags |= RecordTypes.FLAG_SPRINT;
        } else if (n.contains("SPRINTING")) {
            buf.liveFlags &= ~RecordTypes.FLAG_SPRINT;
        }
        if (n.contains("SNEAK") && n.contains("START")) {
            buf.liveFlags |= RecordTypes.FLAG_SNEAK;
        } else if (n.contains("SNEAK")) {
            buf.liveFlags &= ~RecordTypes.FLAG_SNEAK;
        }
        if (n.contains("ELYTRA") || n.contains("FLYING_WITH")) {
            int tick = clock.tick();
            int ord = buf.nextOrder(tick);
            buf.writeEvent(RecordTypes.ELYTRA, tick, clock.nsOffset(nanos), ord, nanos, 1, 0, 0, 0, 0, 0);
            buf.liveFlags |= RecordTypes.FLAG_ELYTRA;
        }
    }

    public void onSettings(PacketReceiveEvent event, PlayerRingBuffer buf) {
        var pkt = new WrapperPlayClientSettings(event);
        buf.mainHand = pkt.getMainHand() == HumanoidArm.LEFT ? (byte) 1 : 0;
    }

    public void onPong(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientPong(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.RTT, tick, clock.nsOffset(nanos), ord, nanos, pkt.getId(), buf.lastRttMs, 1, 0, 0, 0);
    }

    public void onKeepAlive(PacketReceiveEvent event, PlayerRingBuffer buf, TickClock clock, long nanos) {
        var pkt = new WrapperPlayClientKeepAlive(event);
        int tick = clock.tick();
        int ord = buf.nextOrder(tick);
        buf.writeEvent(RecordTypes.RTT, tick, clock.nsOffset(nanos), ord, nanos, pkt.getId(), buf.pingMs, 2, 0, 0, 0);
    }
}
