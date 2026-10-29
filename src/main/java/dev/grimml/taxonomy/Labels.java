package dev.grimml.taxonomy;

import java.util.List;
import java.util.Locale;

public final class Labels {

    public static final String LEGIT = "LEGIT_CONFIRMED";
    public static final String CHEAT = "CHEAT";
    public static final List<String> ALL = List.of(LEGIT, CHEAT);

    public static final int ID_UNSET = 0;
    public static final int ID_LEGIT = 1;
    public static final int ID_CHEAT = 2;

    private Labels() {}

    public static int id(String name) {
        if (name == null) {
            return ID_UNSET;
        }
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LEGIT_CONFIRMED", "LEGIT" -> ID_LEGIT;
            case "CHEAT" -> ID_CHEAT;
            default -> ID_UNSET;
        };
    }

    public static String name(int id) {
        return switch (id) {
            case ID_LEGIT -> LEGIT;
            case ID_CHEAT -> CHEAT;
            default -> "";
        };
    }

    public static boolean known(String name) {
        return id(name) != ID_UNSET;
    }
}
