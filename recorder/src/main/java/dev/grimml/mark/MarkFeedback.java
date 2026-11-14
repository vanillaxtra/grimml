package dev.grimml.mark;

import dev.grimml.GrimMLPlugin;
import dev.grimml.buffer.PlayerRingBuffer;
import dev.grimml.taxonomy.Labels;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

// runs on main thread every 4 ticks; sends action bar to each marked player
public final class MarkFeedback implements Runnable {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final GrimMLPlugin plugin;

    public MarkFeedback(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (MarkTracker.Open o : plugin.marks().allOpen()) {
            Player p = Bukkit.getPlayer(o.uuid);
            if (p == null || !p.isOnline()) continue;
            PlayerRingBuffer buf = plugin.registry().get(o.uuid);
            if (buf == null) continue;
            p.sendActionBar(MM.deserialize(bar(o, buf)));
        }
    }

    public static String clientNameOf(String[] kv) {
        if (kv == null) return "";
        for (String pair : kv) {
            if (pair.regionMatches(true, 0, "client-name=", 0, 12)) {
                return pair.substring(12);
            }
        }
        return "";
    }

    private String bar(MarkTracker.Open o, PlayerRingBuffer buf) {
        long elapsedSec = (System.nanoTime() - o.startNanos) / 1_000_000_000L;
        String elapsed = String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60);

        String label = Labels.name(o.labelId);
        String module = plugin.taxonomy().moduleName(o.moduleId);
        String clientName = clientNameOf(o.kv);

        int ping = buf.pingMs;
        int jitter = buf.jitterMs;
        double mspt = plugin.clock().mspt();
        int frames = buf.frames.count();

        String dotColor = qualityDot(ping, jitter, mspt);
        String pingColor = ping < 80 ? "<green>" : (ping < 180 ? "<yellow>" : "<red>");
        String jitterColor = jitter < 30 ? "<green>" : (jitter < 70 ? "<yellow>" : "<red>");
        String msptColor = mspt < 15 ? "<green>" : (mspt < 30 ? "<yellow>" : "<red>");
        String frColor = frames > 60 ? "<green>" : (frames > 20 ? "<yellow>" : "<red>");

        StringBuilder sb = new StringBuilder();
        sb.append(dotColor).append("◉ <white>").append(label);
        if (!module.isEmpty()) sb.append(" ").append(module);
        if (!clientName.isEmpty()) sb.append(" <gray>(</gray><white>").append(clientName).append("</white><gray>)</gray>");
        sb.append("</white> <gray>•</gray> <aqua>").append(elapsed).append("</aqua>");
        sb.append(" <gray>•</gray> <white>").append(o.id).append("</white>");
        sb.append(" <gray>|</gray> ").append(pingColor).append("p:").append(ping).append("ms</>");
        sb.append(" <gray>|</gray> ").append(jitterColor).append("j:").append(jitter).append("ms</>");
        sb.append(" <gray>|</gray> ").append(msptColor).append(String.format(Locale.ROOT, "%.0f", mspt)).append("ms</>");
        sb.append(" <gray>|</gray> ").append(frColor).append(frames).append("f</>");
        return sb.toString();
    }

    private static String qualityDot(int ping, int jitter, double mspt) {
        if (ping >= 180 || mspt >= 40) return "<red>";
        if (ping >= 80 || jitter >= 30 || mspt >= 15) return "<yellow>";
        return "<green>";
    }
}
