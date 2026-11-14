package dev.grimml.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class Anonymizer {

    private Anonymizer() {}

    public static long anonId(UUID uuid, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(uuid.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] dig = md.digest();
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (dig[i] & 0xFFL);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required on every JDK we target
            return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        }
    }

    public static long newSessionId() {
        return ThreadLocalRandom.current().nextLong() ^ System.nanoTime();
    }
}
