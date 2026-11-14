package dev.grimml.item;

public final class ItemCategories {
    public static final byte OTHER = 0;
    public static final byte SWORD = 1;
    public static final byte AXE = 2;
    public static final byte MACE = 3;
    public static final byte SPEAR = 4;
    public static final byte TRIDENT = 5;
    public static final byte BOW = 6;
    public static final byte CROSSBOW = 7;
    public static final byte SHIELD = 8;
    public static final byte TOTEM = 9;
    public static final byte GAPPLE = 10;
    public static final byte EGAPPLE = 11;
    public static final byte POTION = 12;
    public static final byte PEARL = 13;
    public static final byte WIND_CHARGE = 14;
    public static final byte COBWEB = 15;
    public static final byte BLOCK = 16;
    public static final byte WATER_BUCKET = 17;
    public static final byte LAVA_BUCKET = 18;
    public static final byte FOOD = 19;
    public static final byte FIST = 20;

    private ItemCategories() {}

    public static byte of(String key) {
        if (key == null || key.isEmpty() || key.equals("air")) {
            return FIST;
        }
        if (key.endsWith("_sword")) return SWORD;
        if (key.endsWith("_axe")) return AXE;
        if (key.endsWith("_slab") || key.endsWith("_stairs") || key.endsWith("_log")
                || key.endsWith("_planks") || key.endsWith("_wool") || key.endsWith("_concrete")
                || key.endsWith("_terracotta") || key.endsWith("_block") || key.endsWith("_wood")) {
            return BLOCK;
        }
        return switch (key) {
            case "mace" -> MACE;
            case "spear" -> SPEAR;
            case "trident" -> TRIDENT;
            case "bow" -> BOW;
            case "crossbow" -> CROSSBOW;
            case "shield" -> SHIELD;
            case "totem_of_undying" -> TOTEM;
            case "golden_apple" -> GAPPLE;
            case "enchanted_golden_apple" -> EGAPPLE;
            case "potion", "splash_potion", "lingering_potion" -> POTION;
            case "ender_pearl" -> PEARL;
            case "wind_charge" -> WIND_CHARGE;
            case "cobweb" -> COBWEB;
            case "water_bucket" -> WATER_BUCKET;
            case "lava_bucket" -> LAVA_BUCKET;
            case "firework_rocket" -> OTHER;
            default -> OTHER;
        };
    }

    public static int nameId(String key) {
        if (key == null) {
            return 0;
        }
        int h = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
