package dev.grimml.inference;

import dev.grimml.GrimMLPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

// staff chat alert plus a pending review row; the review verdict is what feeds training labels
public final class AlertNotifier implements InferenceService.AlertSink {

    public static final String PERMISSION = "grimml.alerts";

    private final GrimMLPlugin plugin;

    public AlertNotifier(GrimMLPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void alert(InferenceService.Job job, double proba, ViolationBuffer.State state) {
        long reviewId = -1L;
        if (plugin.evidenceStore() != null) {
            reviewId = plugin.evidenceStore().writeModelAlert(job.anon(), job.session(), job.epochMs(),
                    job.epochMs() + job.durationMs(), proba);
        }
        String anon36 = Long.toUnsignedString(job.anon(), 36);
        double score = state == null ? proba : state.score;
        int windows = state == null ? 1 : state.windows;
        long id = reviewId;
        plugin.tasks().global(() -> broadcast(job, anon36, proba, score, windows, id));
    }

    private void broadcast(InferenceService.Job job, String anon36, double proba, double score, int windows, long reviewId) {
        UUID uuid = plugin.registry() == null ? null : plugin.registry().uuidOfAnon(job.anon());
        Player target = uuid == null ? null : Bukkit.getPlayer(uuid);
        String who = target != null ? target.getName() : anon36;
        String mini = String.format(Locale.ROOT,
                "<dark_gray>[<red>grimml</red>]</dark_gray> <white>%s</white>%s <gray>model %.2f score %.2f over %d windows%s",
                who, job.testAccount() ? " <yellow>(test)</yellow>" : "", proba, score, windows,
                reviewId > 0 ? " review " + reviewId + "</gray> <dark_gray>/grimml review mark " + reviewId + " confirmed|legit|unsure" : "</gray>");
        plugin.getLogger().info("model alert " + anon36 + " proba " + String.format(Locale.ROOT, "%.3f", proba)
                + " score " + String.format(Locale.ROOT, "%.3f", score) + " review " + reviewId);
        var msg = MiniMessage.miniMessage().deserialize(mini);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) {
                plugin.tasks().forEntity(p, () -> p.sendMessage(msg));
            }
        }
    }
}
