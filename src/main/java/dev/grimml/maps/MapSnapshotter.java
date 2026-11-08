package dev.grimml.maps;

import dev.grimml.GrimMLPlugin;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// one snapshot per map id and world; chunk reads run on the owning region a few chunks per tick
public final class MapSnapshotter {

    private static final int CHUNKS_PER_TICK = 4;

    private final GrimMLPlugin plugin;
    private final Path dir;
    private final Set<String> done = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public MapSnapshotter(GrimMLPlugin plugin, Path dir) {
        this.plugin = plugin;
        this.dir = dir;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.gmlmap")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    done.add(n.substring(0, n.length() - ".gmlmap".length()));
                }
            } catch (IOException e) {
                plugin.getLogger().warning("map snapshot scan failed: " + e.getMessage());
            }
        }
    }

    // relative position mode exists to hide survival coordinates, a block snapshot would leak them
    public void maybeCapture(Location at) {
        if (!plugin.config().mapSnapshotEnabled() || plugin.config().relativePositions() || at.getWorld() == null) {
            return;
        }
        String key = MapRegion.fileKey(plugin.config().mapId(), at.getWorld().getName());
        if (done.contains(key) || inFlight.contains(key)) {
            return;
        }
        capture(at);
    }

    public String capture(Location at) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        String key = MapRegion.fileKey(plugin.config().mapId(), world.getName());
        if (!inFlight.add(key)) {
            return null;
        }
        MapRegion region = MapRegion.around(plugin.config().mapId(), world.getName(), plugin.config().serverId(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                plugin.config().mapSnapshotRadius(), plugin.config().mapSnapshotHeight(),
                world.getMinHeight(), world.getMaxHeight());
        int cx0 = region.minX() >> 4;
        int cx1 = (region.minX() + region.sizeX() - 1) >> 4;
        int cz0 = region.minZ() >> 4;
        int cz1 = (region.minZ() + region.sizeZ() - 1) >> 4;
        int total = (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        Map<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(total);
        int i = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                int fx = cx;
                int fz = cz;
                plugin.tasks().atChunk(world, cx, cz, 1L + i / CHUNKS_PER_TICK, () -> {
                    try {
                        if (world.isChunkLoaded(fx, fz)) {
                            chunks.put(MapRegion.chunkKey(fx, fz), world.getChunkAt(fx, fz).getChunkSnapshot(false, false, false));
                        }
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            plugin.tasks().async(() -> encode(key, region, chunks));
                        }
                    }
                });
                i++;
            }
        }
        return key;
    }

    private void encode(String key, MapRegion region, Map<Long, ChunkSnapshot> chunks) {
        try {
            MapFileWriter.write(dir.resolve(key + ".gmlmap"), region, chunks);
            done.add(key);
            plugin.getLogger().info("map snapshot " + key + " saved (" + chunks.size() + " chunks)");
        } catch (IOException e) {
            plugin.getLogger().warning("map snapshot " + key + " failed: " + e.getMessage());
        } finally {
            inFlight.remove(key);
        }
    }
}
