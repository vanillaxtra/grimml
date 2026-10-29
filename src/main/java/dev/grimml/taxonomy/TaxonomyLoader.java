package dev.grimml.taxonomy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TaxonomyLoader {

    private TaxonomyLoader() {}

    public static Taxonomy load(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("taxonomy parse: " + e.getMessage(), e);
        }
        return parse(yaml, hash64(raw));
    }

    public static Taxonomy parse(YamlConfiguration yaml, long hash) {
        int version = yaml.getInt("version", 1);
        List<String> clients = yaml.getStringList("clients");
        Map<String, ModuleSpec> modules = new LinkedHashMap<>();
        ConfigurationSection mods = yaml.getConfigurationSection("modules");
        if (mods != null) {
            for (String name : mods.getKeys(false)) {
                ConfigurationSection m = mods.getConfigurationSection(name);
                if (m == null) {
                    continue;
                }
                List<String> grim = m.getStringList("expected_grim");
                Map<String, SettingSpec> settings = parseSettings(m.getConfigurationSection("settings"));
                modules.put(name, new ModuleSpec(name, grim, settings));
            }
        }
        return new Taxonomy(version, hash, List.copyOf(clients), Map.copyOf(modules));
    }

    static Map<String, SettingSpec> parseSettings(ConfigurationSection sec) {
        Map<String, SettingSpec> out = new LinkedHashMap<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            List<String> enums = sec.getStringList(key);
            if (!enums.isEmpty()) {
                out.put(key, new SettingSpec(key, SettingSpec.Kind.ENUM, enums.toArray(String[]::new)));
                continue;
            }
            String type = sec.getString(key, "string");
            SettingSpec.Kind kind = switch (type.toLowerCase(Locale.ROOT)) {
                case "int", "integer" -> SettingSpec.Kind.INT;
                case "float", "double", "number" -> SettingSpec.Kind.FLOAT;
                default -> SettingSpec.Kind.STRING;
            };
            out.put(key, new SettingSpec(key, kind, new String[0]));
        }
        return out;
    }

    public static String validateKv(ModuleSpec module, List<String> kv) {
        if (module == null) {
            return "unknown module";
        }
        for (String pair : kv) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return "bad setting " + pair;
            }
            String key = pair.substring(0, eq);
            String val = pair.substring(eq + 1);
            if (key.equalsIgnoreCase("expected")) {
                continue;
            }
            SettingSpec spec = module.settings.get(key);
            if (spec == null) {
                for (var e : module.settings.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(key)) {
                        spec = e.getValue();
                        break;
                    }
                }
            }
            if (spec == null) {
                return "unknown setting " + key;
            }
            String err = spec.validate(val);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    public static String validateFree(List<String> kv) {
        for (String pair : kv) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return "bad setting " + pair;
            }
        }
        return null;
    }

    public static List<String> kvPairs(String[] args, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].indexOf('=') > 0) {
                out.add(args[i]);
            }
        }
        return out;
    }

    static long hash64(byte[] raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw);
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[i] & 0xFFL);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            return raw.length;
        }
    }
}
