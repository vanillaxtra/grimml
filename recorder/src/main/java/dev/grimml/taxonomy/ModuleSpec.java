package dev.grimml.taxonomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleSpec {

    public final String name;
    public final List<String> expectedGrim;
    public final Map<String, SettingSpec> settings;

    public ModuleSpec(String name, List<String> expectedGrim, Map<String, SettingSpec> settings) {
        this.name = name;
        this.expectedGrim = expectedGrim;
        this.settings = settings;
    }

    public static ModuleSpec empty(String name) {
        return new ModuleSpec(name, List.of(), new LinkedHashMap<>());
    }
}
