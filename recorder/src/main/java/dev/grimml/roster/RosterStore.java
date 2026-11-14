package dev.grimml.roster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// append-only csv off the server thread
public final class RosterStore {

    private static final String HEADER = "uuid,anon_id,label,source,reason,at_epoch_ms,valid_from_epoch_ms,server_id\n";
    private final Path file;
    private final Object lock = new Object();

    public RosterStore(Path file) {
        this.file = file;
    }

    public void append(String uuid, String anonId, String label, String source, String reason, long at, long validFrom, String serverId) throws IOException {
        String line = uuid + "," + anonId + "," + label + "," + source + ","
                + csv(reason) + "," + at + "," + validFrom + "," + csv(serverId) + "\n";
        synchronized (lock) {
            if (!Files.exists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').replace(',', ' ').replace('"', '\'').trim();
        if (t.length() > 200) {
            return t.substring(0, 200);
        }
        return t;
    }
}
