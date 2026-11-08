package dev.grimml.maps;

public record MapRegion(String mapId, String world, String serverId,
                        int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {

    public static MapRegion around(String mapId, String world, String serverId, int cx, int cy, int cz,
                                   int radius, int height, int worldMinY, int worldMaxY) {
        int minY = Math.max(worldMinY, cy - height);
        int maxY = Math.min(worldMaxY - 1, cy + height);
        return new MapRegion(mapId, world, serverId, cx - radius, minY, cz - radius,
                radius * 2 + 1, Math.max(1, maxY - minY + 1), radius * 2 + 1);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static String fileKey(String mapId, String world) {
        return clean(mapId) + "__" + clean(world);
    }

    static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }
}
