package dev.grimml.grim;

import java.util.Locale;

public enum CheckCategory {
    COMBAT,
    MOVEMENT,
    WORLD,
    PACKET;

    public static CheckCategory parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return PACKET;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "combat" -> COMBAT;
            case "movement" -> MOVEMENT;
            case "world" -> WORLD;
            default -> PACKET;
        };
    }

    public boolean combatRelevant() {
        return this == COMBAT;
    }
}
