package dev.grimml.grim;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class GrimWeightsLoader {

    private GrimWeightsLoader() {}

    public static GrimWeights load(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(raw);
        } catch (Exception e) {
            throw new IOException("grim-weights parse: " + e.getMessage(), e);
        }
        return parse(yaml);
    }

    public static GrimWeights parse(YamlConfiguration yaml) {
        int version = yaml.getInt("version", 1);
        double unknownW = yaml.getDouble("defaults.unknown-weight", 0.0);
        CheckCategory unknownC = CheckCategory.parse(yaml.getString("defaults.unknown-category", "packet"));
        double unknownT = yaml.getDouble("defaults.unknown-trust", 0.2);
        int maxPing = yaml.getInt("lag.max-ping-ms", 180);
        int maxJitter = yaml.getInt("lag.max-jitter-ms", 80);
        double maxMspt = yaml.getDouble("lag.max-mspt", 40.0);
        int settle = yaml.getInt("lag.settle-seconds", 10);
        double half = yaml.getDouble("score.half-life-seconds", 1800);
        double thresh = yaml.getDouble("score.promote-threshold", 8.0);
        int distinct = yaml.getInt("score.promote-distinct-checks", 2);
        int window = yaml.getInt("score.promote-window-seconds", 3600);
        int sessions = yaml.getInt("score.promote-same-check-sessions", 2);
        double scale = yaml.getDouble("score.confidence-scale", 0.12);
        Map<String, CheckWeight> checks = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection("checks");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(name);
                if (c == null) {
                    continue;
                }
                CheckWeight w = new CheckWeight(
                        name,
                        c.getDouble("weight", unknownW),
                        CheckCategory.parse(c.getString("category", "packet")),
                        c.getDouble("trust", unknownT)
                );
                checks.put(name.toLowerCase(Locale.ROOT), w);
            }
        }
        return new GrimWeights(version, unknownW, unknownC, unknownT, maxPing, maxJitter, maxMspt, settle,
                half, thresh, distinct, window, sessions, scale, Map.copyOf(checks));
    }
}
