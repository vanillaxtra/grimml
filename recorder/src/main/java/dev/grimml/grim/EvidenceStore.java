package dev.grimml.grim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// sidecar only never rewrite recordings never write uuid or name
public final class EvidenceStore {

    private static final String LABEL_HEADER = "kind,anon_id,session_id,from_ms,to_ms,label,source,confidence,score,checks,review_id\n";
    private static final String REVIEW_HEADER = "review_id,anon_id,session_id,from_ms,to_ms,label,confidence,status\n";

    private final Path labels;
    private final Path review;
    private final AtomicLong nextReview = new AtomicLong(1);
    private final Object lock = new Object();

    public EvidenceStore(Path labels, Path review) {
        this.labels = labels;
        this.review = review;
    }

    public long writeWindow(long anon, long session, long from, long to, double confidence, double score, String checks) {
        long id = nextReview.getAndIncrement();
        append(labels, LABEL_HEADER, csv("window", anon, session, from, to, "GRIM_WEAK", "grim-auto-window", confidence, score, checks, id));
        append(review, REVIEW_HEADER, id + "," + Long.toUnsignedString(anon, 36) + "," + Long.toUnsignedString(session, 36)
                + "," + from + "," + to + ",GRIM_WEAK," + fmt(confidence) + ",pending\n");
        return id;
    }

    public void writePlayer(long anon, long session, long from, long to, double confidence, double score, String checks) {
        append(labels, LABEL_HEADER, csv("player", anon, session, from, to, "GRIM_PLAYER_SUSPECT", "grim-auto-player", confidence, score, checks, 0));
    }

    public void writeSession(long anon, long session, long from, long to, double score, String checks) {
        append(labels, LABEL_HEADER, csv("session", anon, session, from, to, "GRIM_SESSION", "grim-auto-player", 0, score, checks, 0));
    }

    public String nextPending() {
        synchronized (lock) {
            List<String> lines = readLines(review);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.endsWith(",pending")) {
                    return line;
                }
            }
        }
        return null;
    }

    public boolean markReview(long id, String status) {
        if (!status.equals("confirmed") && !status.equals("legit") && !status.equals("unsure")) {
            return false;
        }
        synchronized (lock) {
            List<String> lines = readLines(review);
            boolean found = false;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith(id + ",")) {
                    int last = line.lastIndexOf(',');
                    if (last < 0) {
                        continue;
                    }
                    lines.set(i, line.substring(0, last + 1) + status);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
            writeAll(review, lines);
        }
        if (status.equals("confirmed") || status.equals("legit")) {
            String label = status.equals("confirmed") ? "REPLAY_CONFIRMED" : "LEGIT_CONFIRMED";
            append(labels, LABEL_HEADER, csv("review", 0, 0, 0, 0, label, "replay-confirmed", status.equals("confirmed") ? 1 : 0, 0, "review:" + id, id));
        }
        return true;
    }

    static String csv(String kind, long anon, long session, long from, long to, String label, String source,
                      double confidence, double score, String checks, long reviewId) {
        return kind + "," + Long.toUnsignedString(anon, 36) + "," + Long.toUnsignedString(session, 36)
                + "," + from + "," + to + "," + label + "," + source + "," + fmt(confidence) + "," + fmt(score)
                + "," + clean(checks) + "," + reviewId + "\n";
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(',', ';').replace('\n', ' ');
    }

    private void append(Path file, String header, String line) {
        synchronized (lock) {
            try {
                if (!Files.exists(file)) {
                    Path parent = file.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(file, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    private List<String> readLines(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void writeAll(Path file, List<String> lines) {
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
