package dev.grimml.item;

import com.github.retrooper.packetevents.protocol.item.ItemStack;

public final class ItemUtil {

    private ItemUtil() {}

    public static byte category(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemCategories.FIST;
        }
        return ItemCategories.of(stack.getType().getName().getKey());
    }

    public static int nameId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return ItemCategories.nameId(stack.getType().getName().getKey());
    }
}
