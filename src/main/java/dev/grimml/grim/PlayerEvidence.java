package dev.grimml.grim;

import java.util.Arrays;

public final class PlayerEvidence {

    public final long anonId;
    public double score;
    public long lastNanos;
    public long sessionId;
    public boolean promoted;
    public int windowFlags;
    public int lastCheckHash;
    final CheckHit[] hits = new CheckHit[16];
    int hitCount;

    public PlayerEvidence(long anonId) {
        this.anonId = anonId;
    }

    public void note(int checkHash, long sessionId, long nanos, long windowNanos) {
        this.sessionId = sessionId;
        lastCheckHash = checkHash;
        CheckHit h = find(checkHash);
        if (h == null) {
            if (hitCount < hits.length) {
                h = new CheckHit(checkHash);
                hits[hitCount++] = h;
            } else {
                h = hits[0];
                h.checkHash = checkHash;
                h.count = 0;
                h.sessions = 0;
                Arrays.fill(h.sessionIds, 0L);
            }
        }
        h.count++;
        h.lastNanos = nanos;
        h.addSession(sessionId);
        lastNanos = nanos;
    }

    public int distinctInWindow(long now, long windowNanos) {
        int n = 0;
        for (int i = 0; i < hitCount; i++) {
            if (now - hits[i].lastNanos <= windowNanos) {
                n++;
            }
        }
        return n;
    }

    public int sessionsFor(int checkHash) {
        CheckHit h = find(checkHash);
        return h == null ? 0 : h.sessions;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hitCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Integer.toHexString(hits[i].checkHash)).append(':').append(hits[i].count);
        }
        return sb.toString();
    }

    CheckHit find(int checkHash) {
        for (int i = 0; i < hitCount; i++) {
            if (hits[i].checkHash == checkHash) {
                return hits[i];
            }
        }
        return null;
    }

    static final class CheckHit {
        int checkHash;
        int count;
        long lastNanos;
        int sessions;
        final long[] sessionIds = new long[4];

        CheckHit(int checkHash) {
            this.checkHash = checkHash;
        }

        void addSession(long sessionId) {
            for (int i = 0; i < sessions && i < sessionIds.length; i++) {
                if (sessionIds[i] == sessionId) {
                    return;
                }
            }
            if (sessions < sessionIds.length) {
                sessionIds[sessions++] = sessionId;
            }
        }
    }
}
