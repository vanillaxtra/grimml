package dev.grimml.detect.overlay;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Staff overlay: a text hologram riding on a flagged player's head, shown only to staff who turned it on.
 *
 * The hologram exists only in the staff member's client (packets, no server entity), so it never teleports, never
 * blocks the player from being teleported and never shows in their passenger list. The client carries it as a
 * passenger, so it follows the head every frame with zero extra packets.
 *
 * Per viewer we follow what their client has: player spawns (mount the hologram after them), entity destroys (destroy
 * it with them), passenger updates (keep it in the list) and respawns (the client threw everything away). All of a
 * viewer's state changes and sends run on that viewer's netty event loop, so they stay in order with the server's
 * own packets.
 *
 * Text display metadata indices are the 1.20.2 - 1.21.x layout (UNVERIFIED on 26.x).
 */
public final class Overlay extends PacketListenerAbstract {

    public record Settings(boolean enabled, float height, float scale, int maxLines, long keepMs, boolean showWatching,
                           boolean seeThrough, int background, String flagged, String watching, String rule) {
        public static final long LIVE_KEEP_MS = 30_000L;
    }

    // the hologram of one target player
    private static final class Holo {
        final UUID target;
        final int targetEntity;
        final int fakeId;
        final UUID fakeUuid = UUID.randomUUID();
        volatile String json; // null once removed

        Holo(UUID target, int targetEntity, int fakeId) {
            this.target = target;
            this.targetEntity = targetEntity;
            this.fakeId = fakeId;
        }
    }

    // what one viewer's client has; only touched on that viewer's event loop (synchronized for safety)
    private static final class Viewer {
        final UUID id;
        final User user;
        final Set<Integer> spawned = new HashSet<>();            // player entities the client has
        final Map<Integer, double[]> spawnPos = new HashMap<>();  // where they were spawned
        final Map<Integer, int[]> passengers = new HashMap<>();   // the server's own passenger list per vehicle
        final Map<Integer, Holo> mounted = new HashMap<>();       // target entity -> hologram on it
        final Map<Integer, String> sentJson = new HashMap<>();

        Viewer(UUID id, User user) {
            this.id = id;
            this.user = user;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, OverlayLines> lines = new ConcurrentHashMap<>();
    private final Map<UUID, Holo> holos = new ConcurrentHashMap<>();
    private final Map<Integer, Holo> holoByEntity = new ConcurrentHashMap<>();
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> on = ConcurrentHashMap.newKeySet();
    private final Function<UUID, double[]> position;     // target uuid -> last position, or null if offline
    private final Function<UUID, Integer> entityIdOf;    // target uuid -> entity id, or null if offline
    private final Function<String, String> friendly;
    private final IntSupplier nextEntityId;
    private volatile Settings settings;

    public Overlay(Settings settings, Function<UUID, Integer> entityIdOf, Function<UUID, double[]> position,
                   Function<String, String> friendly, IntSupplier nextEntityId) {
        super(PacketListenerPriority.HIGHEST); // after other plugins changed passengers/destroys, before MONITOR
        this.settings = settings;
        this.entityIdOf = entityIdOf;
        this.position = position;
        this.friendly = friendly;
        this.nextEntityId = nextEntityId;
    }

    // new settings: every hologram is spawned again with them (height, scale, background), text on the next refresh
    public void settings(Settings s) {
        this.settings = s;
        for (Viewer v : viewers.values()) {
            ChannelHelper.runInEventLoop(v.user.getChannel(), () -> {
                synchronized (v) {
                    for (Holo h : new ArrayList<>(v.mounted.values())) {
                        unmount(v, h);
                        sync(v, h);
                    }
                }
            });
        }
    }

    // ---- data from the scanner (scan thread) ----

    public void alert(UUID target, String check, double confidence, int flagged, int windows) {
        if (settings.enabled()) {
            lines.computeIfAbsent(target, k -> new OverlayLines())
                    .alert(check, confidence, flagged, windows, System.currentTimeMillis());
        }
    }

    public void live(UUID target, String check, int windows, int flagged, double mean) {
        if (!settings.enabled()) {
            return;
        }
        OverlayLines l = flagged > 0 ? lines.computeIfAbsent(target, k -> new OverlayLines()) : lines.get(target);
        if (l != null) {
            l.live(check, windows, flagged, mean, System.currentTimeMillis());
        }
    }

    // renders every target's text and pushes the changes to viewers (scan thread, every scan)
    public void refresh() {
        Settings s = settings;
        long now = System.currentTimeMillis();
        for (var e : lines.entrySet()) {
            UUID target = e.getKey();
            OverlayLines l = e.getValue();
            String json = null;
            if (s.enabled() && !l.prune(now, s.keepMs(), Settings.LIVE_KEEP_MS)) {
                json = render(l.lines(now, s.keepMs(), Settings.LIVE_KEEP_MS, s.showWatching(), s.maxLines()), s);
            }
            if (json == null) {
                lines.remove(target, l);
                remove(target);
                continue;
            }
            Holo h = holos.get(target);
            if (h == null) {
                Integer ent = entityIdOf.apply(target);
                if (ent == null) {
                    lines.remove(target, l);
                    continue;
                }
                h = new Holo(target, ent, nextEntityId.getAsInt());
                holos.put(target, h);
                holoByEntity.put(ent, h);
            }
            if (!json.equals(h.json)) {
                h.json = json;
                pushAll(h);
            }
        }
    }

    // MiniMessage lines -> one JSON component, or null when there is nothing to show
    String render(List<OverlayLines.Line> ls, Settings s) {
        if (ls.isEmpty()) {
            return null;
        }
        List<Component> parts = new ArrayList<>();
        for (OverlayLines.Line l : ls) {
            String fmt = switch (l.kind()) {
                case FLAGGED -> s.flagged();
                case WATCHING -> s.watching();
                case RULE -> s.rule();
            };
            TagResolver tags = TagResolver.resolver(
                    Placeholder.unparsed("check", friendly.apply(l.check())),
                    Placeholder.unparsed("confidence", String.valueOf(Math.round(l.confidence() * 100))),
                    Placeholder.unparsed("flagged", String.valueOf(Math.max(0, l.flagged()))),
                    Placeholder.unparsed("windows", String.valueOf(Math.max(0, l.windows()))),
                    Placeholder.unparsed("count", String.valueOf(l.count())));
            parts.add(MM.deserialize(fmt, tags));
        }
        return GsonComponentSerializer.gson().serialize(Component.join(
                net.kyori.adventure.text.JoinConfiguration.newlines(), parts));
    }

    // a target left: their hologram goes away for everyone
    public void forgetTarget(UUID target) {
        lines.remove(target);
        remove(target);
    }

    private void remove(UUID target) {
        Holo h = holos.remove(target);
        if (h != null) {
            holoByEntity.remove(h.targetEntity, h);
            h.json = null;
            pushAll(h);
        }
    }

    // ---- viewers (main thread) ----

    // turns a viewer's overlay on or off; returns the new state
    public boolean toggle(UUID viewer) {
        boolean now = !on.remove(viewer);
        if (now) {
            on.add(viewer);
        }
        Viewer v = viewers.get(viewer);
        if (v != null) {
            ChannelHelper.runInEventLoop(v.user.getChannel(), () -> {
                for (Holo h : holoByEntity.values()) {
                    sync(v, h);
                }
                for (Holo h : new ArrayList<>(v.mounted.values())) {
                    sync(v, h); // turned off: unmount what is left
                }
            });
        }
        return now;
    }

    public boolean isOn(UUID viewer) {
        return on.contains(viewer);
    }

    // a viewer lost the permission
    public void turnOff(UUID viewer) {
        if (on.contains(viewer)) {
            toggle(viewer);
        }
    }

    public void viewerQuit(UUID viewer) {
        on.remove(viewer);
        viewers.remove(viewer);
    }

    // plugin disabled: take every hologram out of every client
    public void shutdown() {
        on.clear();
        for (Viewer v : viewers.values()) {
            ChannelHelper.runInEventLoop(v.user.getChannel(), () -> {
                for (Holo h : new ArrayList<>(v.mounted.values())) {
                    sync(v, h);
                }
            });
        }
        holos.clear();
        holoByEntity.clear();
        lines.clear();
    }

    // a hologram changed: viewers with the overlay on update it (a removed one: everyone who may still have it)
    private void pushAll(Holo h) {
        for (Viewer v : viewers.values()) {
            if (h.json == null || on.contains(v.id)) {
                ChannelHelper.runInEventLoop(v.user.getChannel(), () -> sync(v, h));
            }
        }
    }

    // takes the hologram out of this viewer's client (caller holds the viewer lock)
    private static void unmount(Viewer v, Holo h) {
        if (v.mounted.remove(h.targetEntity, h)) {
            v.sentJson.remove(h.targetEntity);
            v.user.sendPacketSilently(new WrapperPlayServerDestroyEntities(h.fakeId));
        }
    }

    // makes this viewer's client match the hologram (event loop)
    private void sync(Viewer v, Holo h) {
        synchronized (v) {
            String json = h.json;
            boolean show = json != null && !json.isEmpty() && on.contains(v.id)
                    && v.spawned.contains(h.targetEntity) && holoByEntity.get(h.targetEntity) == h;
            Holo cur = v.mounted.get(h.targetEntity);
            if (!show) {
                if (cur == h) {
                    unmount(v, h);
                }
                return;
            }
            if (cur != null && cur != h) {
                unmount(v, cur); // a stale one on this entity
                cur = null;
            }
            if (cur == null) {
                mount(v, h, json);
            } else if (!json.equals(v.sentJson.get(h.targetEntity))) {
                v.user.sendPacketSilently(new WrapperPlayServerEntityMetadata(h.fakeId,
                        List.of(new EntityData<>(23, EntityDataTypes.COMPONENT, json))));
                v.sentJson.put(h.targetEntity, json);
            }
        }
    }

    // spawns the hologram and seats it on the target (caller holds the viewer lock, on the event loop)
    private void mount(Viewer v, Holo h, String json) {
        Settings s = settings;
        double[] pos = v.spawnPos.get(h.targetEntity);
        double[] live = position.apply(h.target);
        if (live != null) {
            pos = live;
        }
        if (pos == null) {
            pos = new double[3];
        }
        v.user.sendPacketSilently(new WrapperPlayServerSpawnEntity(h.fakeId, Optional.of(h.fakeUuid),
                EntityTypes.TEXT_DISPLAY, new Vector3d(pos[0], pos[1] + 1.8, pos[2]), 0f, 0f, 0f, 0, Optional.empty()));
        List<EntityData<?>> d = new ArrayList<>();
        d.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(0f, s.height(), 0f)));   // translation
        d.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(s.scale(), s.scale(), s.scale())));
        d.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) 3));                                // billboard: center
        d.add(new EntityData<>(16, EntityDataTypes.INT, (15 << 4) | (15 << 20)));                    // full bright
        d.add(new EntityData<>(18, EntityDataTypes.FLOAT, 0f));                                     // no shadow
        d.add(new EntityData<>(23, EntityDataTypes.COMPONENT, json));                               // text
        d.add(new EntityData<>(25, EntityDataTypes.INT, s.background()));                          // background ARGB
        d.add(new EntityData<>(27, EntityDataTypes.BYTE, (byte) (0x01 | (s.seeThrough() ? 0x02 : 0)))); // shadowed text
        v.user.sendPacketSilently(new WrapperPlayServerEntityMetadata(h.fakeId, d));
        v.user.sendPacketSilently(new WrapperPlayServerSetPassengers(h.targetEntity,
                withFake(v.passengers.get(h.targetEntity), h.fakeId)));
        v.mounted.put(h.targetEntity, h);
        v.sentJson.put(h.targetEntity, json);
    }

    private static int[] withFake(int[] real, int fake) {
        if (real == null || real.length == 0) {
            return new int[]{fake};
        }
        for (int id : real) {
            if (id == fake) {
                return real;
            }
        }
        int[] out = java.util.Arrays.copyOf(real, real.length + 1);
        out[real.length] = fake;
        return out;
    }

    // ---- packets the server sends to each player (netty thread) ----

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type != PacketType.Play.Server.SPAWN_ENTITY && type != PacketType.Play.Server.DESTROY_ENTITIES
                && type != PacketType.Play.Server.SET_PASSENGERS && type != PacketType.Play.Server.RESPAWN
                && type != PacketType.Play.Server.JOIN_GAME) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        User user = event.getUser();
        UUID id = user.getUUID();
        if (id == null) {
            return;
        }
        Viewer v = viewers.computeIfAbsent(id, k -> new Viewer(k, user));
        synchronized (v) {
            if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                var p = new WrapperPlayServerSpawnEntity(event);
                if (p.getEntityType() != EntityTypes.PLAYER) {
                    return;
                }
                int ent = p.getEntityId();
                v.spawned.add(ent);
                var pos = p.getPosition();
                v.spawnPos.put(ent, new double[]{pos.getX(), pos.getY(), pos.getZ()});
                Holo old = v.mounted.remove(ent); // a re-spawn replaces the player and drops its riders
                v.sentJson.remove(ent);
                Holo h = holoByEntity.get(ent);
                if (old != null || (h != null && on.contains(id))) {
                    event.getPostTasks().add(() -> {          // after the player exists on the client
                        if (old != null) {
                            v.user.sendPacketSilently(new WrapperPlayServerDestroyEntities(old.fakeId));
                        }
                        if (h != null) {
                            sync(v, h);
                        }
                    });
                }
            } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
                var p = new WrapperPlayServerDestroyEntities(event);
                int[] ids = p.getEntityIds();
                List<Integer> extra = null;
                for (int ent : ids) {
                    if (!v.spawned.remove(ent)) {
                        continue;
                    }
                    v.spawnPos.remove(ent);
                    v.passengers.remove(ent);
                    v.sentJson.remove(ent);
                    Holo h = v.mounted.remove(ent);
                    if (h != null) {
                        if (extra == null) {
                            extra = new ArrayList<>();
                        }
                        extra.add(h.fakeId);
                    }
                }
                if (extra != null) {
                    int[] out = java.util.Arrays.copyOf(ids, ids.length + extra.size());
                    for (int i = 0; i < extra.size(); i++) {
                        out[ids.length + i] = extra.get(i);
                    }
                    p.setEntityIds(out);
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
                var p = new WrapperPlayServerSetPassengers(event);
                int vehicle = p.getEntityId();
                if (!v.spawned.contains(vehicle)) {
                    return;
                }
                int[] real = p.getPassengers();
                v.passengers.put(vehicle, real);
                Holo h = v.mounted.get(vehicle);
                if (h != null) {
                    p.setPassengers(withFake(real, h.fakeId)); // or the server's update would throw our rider off
                    event.markForReEncode(true);
                }
            } else {
                // respawn or (re)join: the client starts over, the server re-sends what it should see.
                // A respawn in the same dimension can keep entities, so take ours out to be sure.
                if (!v.mounted.isEmpty()) {
                    int[] fakes = v.mounted.values().stream().mapToInt(h -> h.fakeId).toArray();
                    event.getPostTasks().add(() -> v.user.sendPacketSilently(new WrapperPlayServerDestroyEntities(fakes)));
                }
                v.spawned.clear();
                v.spawnPos.clear();
                v.passengers.clear();
                v.mounted.clear();
                v.sentJson.clear();
            }
        }
    }
}
