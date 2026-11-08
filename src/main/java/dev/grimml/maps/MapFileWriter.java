package dev.grimml.maps;

import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

// GMM1: header, block data palette, deflated varint palette index per block (y then z then x)
public final class MapFileWriter {

    public static final byte[] MAGIC = {'G', 'M', 'M', '1'};
    public static final String UNLOADED = "grimml:unloaded";

    private MapFileWriter() {}

    public static void write(Path file, MapRegion region, Map<Long, ChunkSnapshot> chunks) throws IOException {
        Map<String, Integer> palette = new HashMap<>();
        Map<BlockData, Integer> byState = new HashMap<>();
        List<String> order = new ArrayList<>();
        palette.put(UNLOADED, 0);
        order.add(UNLOADED);
        ByteArrayOutputStream body = new ByteArrayOutputStream(region.sizeX() * region.sizeZ() * 4);
        for (int y = 0; y < region.sizeY(); y++) {
            int wy = region.minY() + y;
            for (int z = 0; z < region.sizeZ(); z++) {
                int wz = region.minZ() + z;
                for (int x = 0; x < region.sizeX(); x++) {
                    int wx = region.minX() + x;
                    ChunkSnapshot snap = chunks.get(MapRegion.chunkKey(wx >> 4, wz >> 4));
                    int idx = 0;
                    if (snap != null) {
                        BlockData data = snap.getBlockData(wx & 15, wy, wz & 15);
                        Integer known = byState.get(data);
                        if (known == null) {
                            known = palette.computeIfAbsent(data.getAsString(), s -> {
                                order.add(s);
                                return order.size() - 1;
                            });
                            byState.put(data, known);
                        }
                        idx = known;
                    }
                    writeVarUnsigned(body, idx);
                }
            }
        }
        byte[] packed = deflate(body.toByteArray());
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
            out.write(MAGIC);
            out.writeByte(1);
            writeStr(out, region.mapId());
            writeStr(out, region.world());
            writeStr(out, region.serverId());
            out.writeLong(System.currentTimeMillis());
            out.writeInt(region.minX());
            out.writeInt(region.minY());
            out.writeInt(region.minZ());
            out.writeInt(region.sizeX());
            out.writeInt(region.sizeY());
            out.writeInt(region.sizeZ());
            out.writeInt(order.size());
            for (String s : order) {
                writeStr(out, s);
            }
            out.writeInt(packed.length);
            out.write(packed);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, 65535);
        out.writeShort(n);
        out.write(b, 0, n);
    }

    static void writeVarUnsigned(ByteArrayOutputStream out, int v) {
        int u = v;
        while ((u & ~0x7F) != 0) {
            out.write((u & 0x7F) | 0x80);
            u >>>= 7;
        }
        out.write(u);
    }

    private static byte[] deflate(byte[] plain) throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream(plain.length / 8 + 64);
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(zipped, def)) {
            dos.write(plain);
        } finally {
            def.end();
        }
        return zipped.toByteArray();
    }
}
