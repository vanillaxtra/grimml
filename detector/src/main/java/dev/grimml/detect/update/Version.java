package dev.grimml.detect.update;

/**
 * This is the version class for the update checker.
 * It reads a version like "v1.2.0-SNAPSHOT" as 1.2.0 (up to 4 parts).
 * It compares part by part, so 1.0.10 is newer than 1.0.9.
 */
public final class Version implements Comparable<Version> {
    public static final Version ZERO = new Version("0.0.0");
    private static final int PARTS = 4;
    private final int[] parts = new int[PARTS];

    public Version(String version) {
        String v = version == null ? "" : version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1).trim();
        }
        v = v.replaceAll("^[^0-9]*", "").replaceAll("[^0-9.].*$", "");
        String[] split = v.isEmpty() ? new String[0] : v.split("[.]");
        for (int i = 0; i < PARTS; i++) {
            try {
                parts[i] = i < split.length ? Integer.parseInt(split[i]) : 0;
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
    }

    @Override
    public int compareTo(Version o) {
        for (int i = 0; i < PARTS; i++) {
            if (parts[i] != o.parts[i]) {
                return Integer.compare(parts[i], o.parts[i]);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        String s = parts[0] + "." + parts[1] + "." + parts[2];
        return parts[3] > 0 ? s + "." + parts[3] : s;
    }
}
