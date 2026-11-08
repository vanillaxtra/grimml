package dev.grimml.item;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.Enchantment;

// combat relevant enchant levels packed into one int per slot
public final class EnchantPacker {

    public static final int DAMAGE_SHIFT = 0;
    public static final int PROTECTION_SHIFT = 4;
    public static final int KNOCKBACK_SHIFT = 8;
    public static final int FIRE_SHIFT = 11;
    public static final int BREACH_SHIFT = 13;
    public static final int WIND_BURST_SHIFT = 16;
    public static final int LUNGE_SHIFT = 19;
    public static final int SWEEPING_SHIFT = 22;
    public static final int FEATHER_SHIFT = 25;
    public static final int QUICK_CHARGE_SHIFT = 28;
    public static final int OTHER_BIT = 1 << 31;

    private EnchantPacker() {}

    public static int pack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            if (!stack.isEnchanted()) {
                return 0;
            }
            int packed = 0;
            for (Enchantment e : stack.getEnchantments()) {
                packed = add(packed, e.getType().getName().getKey(), e.getLevel());
            }
            return packed;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public static int add(int packed, String key, int level) {
        return switch (key) {
            case "sharpness", "smite", "bane_of_arthropods", "power", "impaling", "density" ->
                    max(packed, DAMAGE_SHIFT, 4, level);
            case "protection", "blast_protection", "projectile_protection", "fire_protection" ->
                    max(packed, PROTECTION_SHIFT, 4, level);
            case "knockback", "punch" -> max(packed, KNOCKBACK_SHIFT, 3, level);
            case "fire_aspect", "flame" -> max(packed, FIRE_SHIFT, 2, level);
            case "breach" -> max(packed, BREACH_SHIFT, 3, level);
            case "wind_burst" -> max(packed, WIND_BURST_SHIFT, 3, level);
            case "lunge" -> max(packed, LUNGE_SHIFT, 3, level);
            case "sweeping_edge", "sweeping" -> max(packed, SWEEPING_SHIFT, 3, level);
            case "feather_falling" -> max(packed, FEATHER_SHIFT, 3, level);
            case "quick_charge" -> max(packed, QUICK_CHARGE_SHIFT, 3, level);
            default -> packed | OTHER_BIT;
        };
    }

    public static int level(int packed, int shift, int bits) {
        return (packed >>> shift) & ((1 << bits) - 1);
    }

    private static int max(int packed, int shift, int bits, int level) {
        int cap = (1 << bits) - 1;
        int lv = Math.max(0, Math.min(cap, level));
        int cur = level(packed, shift, bits);
        if (lv <= cur) {
            return packed;
        }
        return (packed & ~(cap << shift)) | (lv << shift);
    }
}
