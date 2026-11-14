package dev.grimml.capture;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.clock.TickClock;
import org.bukkit.entity.Player;

// netty thread dispatch only primitives written into rings
public final class PacketListener extends PacketListenerAbstract {

    private final GrimMLPlugin plugin;
    private final TickClock clock;
    private final FlyingCapture flying = new FlyingCapture();
    private final InputCapture input = new InputCapture();
    private final AttackCapture attack;
    private final HeldSlotCapture held;
    private final InventoryCapture inventory;
    private final ActionCapture actions;
    private final OutgoingCapture outgoing;

    public PacketListener(GrimMLPlugin plugin, TickClock clock) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.clock = clock;
        this.attack = new AttackCapture(plugin);
        this.held = new HeldSlotCapture(plugin);
        this.inventory = new InventoryCapture(plugin);
        this.actions = new ActionCapture(plugin);
        this.outgoing = new OutgoingCapture(plugin);
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
        PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        long nanos = System.nanoTime();
        var type = event.getPacketType();
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            flying.onFlying(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            input.onInput(event, buf);
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            buf.markClientTickEnd();
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            attack.onInteract(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            held.onHeld(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.ANIMATION) {
            actions.onSwing(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            actions.onUseItem(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            actions.onDig(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            actions.onPlace(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            actions.onEntityAction(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.CLIENT_SETTINGS) {
            actions.onSettings(event, buf);
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            inventory.onClick(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            inventory.onClose(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.PONG) {
            actions.onPong(event, buf, clock, nanos);
        } else if (type == PacketType.Play.Client.KEEP_ALIVE) {
            actions.onKeepAlive(event, buf, clock, nanos);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // type first: nearly every outgoing packet is one we ignore, so that path must cost almost nothing
        // (no clock read, no player lookup). a server sends hundreds of packets per player per second
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
            return;
        }
        switch (type) {
            case ENTITY_VELOCITY, ENTITY_STATUS, SET_SLOT, WINDOW_ITEMS, OPEN_WINDOW,
                 PLAYER_POSITION_AND_LOOK, HELD_ITEM_CHANGE -> {
            }
            default -> {
                return;
            }
        }
        if (!plugin.recordingEnabled()) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // always the recipient's own buffer: a connection's thread is the only netty writer of its ring
        PlayerRingBuffer buf = plugin.registry().get(player.getUniqueId());
        if (buf == null) {
            return;
        }
        long nanos = System.nanoTime();
        switch (type) {
            case ENTITY_VELOCITY -> outgoing.onVelocity(event, buf, clock, nanos);
            case ENTITY_STATUS -> outgoing.onStatus(event, buf, clock, nanos);
            case SET_SLOT -> inventory.onSetSlot(event, buf);
            case WINDOW_ITEMS -> inventory.onWindowItems(event, buf);
            case OPEN_WINDOW -> inventory.onOpen(event, buf, clock, nanos);
            case PLAYER_POSITION_AND_LOOK -> outgoing.onTeleport(event, buf, clock, nanos);
            case HELD_ITEM_CHANGE -> outgoing.onHeldSlot(event, buf);
            default -> {
            }
        }
    }
}
