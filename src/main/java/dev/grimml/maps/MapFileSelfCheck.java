package dev.grimml.maps;

import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;

import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// writes a 4x4x4 region with a stone wall at x=2 through proxy chunk data; pytest reads the same file
public final class MapFileSelfCheck {

    public static void main(String[] args) throws Exception {
        BlockData air = block("minecraft:air");
        BlockData stone = block("minecraft:stone");
        ChunkSnapshot chunk = (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
                new Class<?>[]{ChunkSnapshot.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBlockData" -> ((Integer) a[0]) == 2 ? stone : air;
                    case "hashCode" -> 1;
                    case "equals" -> p == a[0];
                    default -> null;
                });
        MapRegion region = new MapRegion("map1", "arena", "ffa", 0, 64, 0, 4, 4, 4);
        Path dir = Path.of(args.length > 0 ? args[0] : "target/selfcheck");
        Path file = dir.resolve(MapRegion.fileKey("map1", "arena") + ".gmlmap");
        MapFileWriter.write(file, region, Map.of(MapRegion.chunkKey(0, 0), chunk));
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'G' || magic[3] != '1' || in.readUnsignedByte() != 1) {
                throw new AssertionError("map magic");
            }
        }
        if (!MapRegion.fileKey("Map 1", "World").equals("map_1__world")) {
            throw new AssertionError("file key " + MapRegion.fileKey("Map 1", "World"));
        }
        System.out.println("map file self-check passed: " + Files.size(file) + " bytes");
    }

    private static BlockData block(String id) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAsString" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> p == a[0];
                    case "toString" -> id;
                    default -> null;
                });
    }
}
