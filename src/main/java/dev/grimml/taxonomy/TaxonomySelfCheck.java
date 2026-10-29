package dev.grimml.taxonomy;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

public final class TaxonomySelfCheck {

    public static void main(String[] args) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        y.set("clients", List.of("vanilla_baseline", "client_a"));
        y.set("modules.aim_assist.expected_grim", List.of("Reach"));
        y.set("modules.aim_assist.settings.strength", List.of("low", "mid", "high"));
        y.set("modules.aim_assist.settings.fov", "int");
        y.set("modules.aim_assist.settings.smoothing", "float");
        Taxonomy t = TaxonomyLoader.parse(y, 123L);
        if (t.version != 1 || t.hash != 123L) {
            throw new AssertionError("version/hash");
        }
        if (t.findClient("CLIENT_A") == null) {
            throw new AssertionError("client");
        }
        if (t.clientId("client_a") != 2 || t.moduleId("aim_assist") != 1) {
            throw new AssertionError("stable ids");
        }
        ModuleSpec m = t.module("aim_assist");
        if (m == null || m.settings.size() != 3) {
            throw new AssertionError("module settings");
        }
        if (TaxonomyLoader.validateKv(m, List.of("strength=high", "fov=90")) != null) {
            throw new AssertionError("valid kv");
        }
        if (TaxonomyLoader.validateKv(m, List.of("strength=nope")) == null) {
            throw new AssertionError("enum should fail");
        }
        if (TaxonomyLoader.validateKv(m, List.of("fov=x")) == null) {
            throw new AssertionError("int should fail");
        }
        System.out.println("taxonomy self-check passed");
    }
}
