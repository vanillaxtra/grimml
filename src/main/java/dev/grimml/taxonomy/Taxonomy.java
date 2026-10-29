package dev.grimml.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Taxonomy {

    public final int version;
    public final long hash;
    public final List<String> clients;
    public final Map<String, ModuleSpec> modules;

    public Taxonomy(int version, long hash, List<String> clients, Map<String, ModuleSpec> modules) {
        this.version = version;
        this.hash = hash;
        this.clients = clients;
        this.modules = modules;
    }

    public static Taxonomy empty() {
        return new Taxonomy(0, 0L, List.of(), Map.of());
    }

    public boolean hasClient(String name) {
        return findClient(name) != null;
    }

    public String findClient(String name) {
        return findIgnoreCase(clients, name);
    }

    public ModuleSpec module(String name) {
        if (name == null) {
            return null;
        }
        ModuleSpec exact = modules.get(name);
        if (exact != null) {
            return exact;
        }
        String key = name.toLowerCase(Locale.ROOT);
        for (var e : modules.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public List<String> moduleNames() {
        return new ArrayList<>(modules.keySet());
    }

    // 1-based yaml order, 0 unset, stable across reloads of the same file
    public int clientId(String name) {
        String found = findClient(name);
        if (found == null) {
            return 0;
        }
        return clients.indexOf(found) + 1;
    }

    public int moduleId(String name) {
        ModuleSpec m = module(name);
        if (m == null) {
            return 0;
        }
        int i = 0;
        for (String k : modules.keySet()) {
            i++;
            if (k.equals(m.name)) {
                return i;
            }
        }
        return 0;
    }

    public String clientName(int id) {
        if (id <= 0 || id > clients.size()) {
            return "";
        }
        return clients.get(id - 1);
    }

    public String moduleName(int id) {
        if (id <= 0) {
            return "";
        }
        int i = 0;
        for (String k : modules.keySet()) {
            i++;
            if (i == id) {
                return k;
            }
        }
        return "";
    }

    static String findIgnoreCase(List<String> list, String name) {
        if (name == null) {
            return null;
        }
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    public static Map<String, ModuleSpec> newModuleMap() {
        return new LinkedHashMap<>();
    }
}
