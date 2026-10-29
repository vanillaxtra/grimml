package dev.grimml.buffer;

import dev.grimml.item.ItemCategories;

// player window slots 0-45 updated from set-slot packets no bukkit
public final class InventoryMirror {

    public static final int SLOTS = 46;
    public static final int HOTBAR0 = 36;
    public static final int OFFHAND = 45;

    private final int[] ids = new int[SLOTS];
    private final byte[] cats = new byte[SLOTS];
    private final int[] enchants = new int[SLOTS];
    private volatile int held = 0;
    private volatile int totemCount = 0;

    public void setHeld(int slot) {
        if (slot >= 0 && slot <= 8) {
            held = slot;
        }
    }

    public int held() {
        return held;
    }

    public void setSlot(int slot, int nameId, byte cat, int ench) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        boolean wasTotem = cats[slot] == ItemCategories.TOTEM;
        boolean isTotem = cat == ItemCategories.TOTEM;
        ids[slot] = nameId;
        cats[slot] = cat;
        enchants[slot] = ench;
        if (wasTotem && !isTotem) {
            totemCount = Math.max(0, totemCount - 1);
        } else if (!wasTotem && isTotem) {
            totemCount++;
        }
    }

    public int idAt(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return 0;
        }
        return ids[slot];
    }

    public byte catAt(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return ItemCategories.FIST;
        }
        return cats[slot];
    }

    public int enchAt(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return 0;
        }
        return enchants[slot];
    }

    public byte heldCat() {
        return catAt(HOTBAR0 + held);
    }

    public int heldId() {
        return idAt(HOTBAR0 + held);
    }

    public int heldEnch() {
        return enchAt(HOTBAR0 + held);
    }

    public byte offCat() {
        return catAt(OFFHAND);
    }

    public int offId() {
        return idAt(OFFHAND);
    }

    public int totemCount() {
        return totemCount;
    }

    public boolean hotbarHasTotem() {
        for (int i = 0; i < 9; i++) {
            if (cats[HOTBAR0 + i] == ItemCategories.TOTEM) {
                return true;
            }
        }
        return cats[OFFHAND] == ItemCategories.TOTEM;
    }
}
