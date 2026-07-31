package com.dwinovo.numen.core.task;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Small adapter giving the companion task layer the {@code SimpleContainer}-style
 * inventory operations it grew up on (count / remove-by-type / add-with-leftover)
 * over the player's native {@link Inventory}. The Mob used a 27-slot
 * SimpleContainer; the player body uses its full Inventory (hotbar + main +
 * armor + offhand), all reachable via {@link Inventory#getContainerSize()} /
 * {@link Inventory#getItem(int)}.
 */
public final class PlayerInv {

    private PlayerInv() {}

    /** Total count of {@code item} across the whole inventory. */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** First slot holding {@code item}, or -1. */
    public static int findSlot(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    /** Remove up to {@code max} of {@code item}; returns how many were removed. */
    public static int remove(Inventory inv, Item item, int max) {
        int removed = 0;
        for (int i = 0; i < inv.getContainerSize() && removed < max; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int take = Math.min(s.getCount(), max - removed);
            s.shrink(take);
            removed += take;
        }
        inv.setChanged();
        return removed;
    }

    /**
     * Add {@code stack} to the inventory; returns whatever didn't fit (empty if
     * all fit). Mirrors {@code SimpleContainer.addItem}'s leftover contract over
     * {@link Inventory#add(ItemStack)} (which mutates the stack down by what fit).
     */
    public static ItemStack add(Inventory inv, ItemStack stack) {
        inv.add(stack);
        return stack;   // Inventory.add consumed what fit; remainder stays here
    }

    /** Number of occupied slots in the 36-slot pickup-capable main inventory. */
    public static int mainSlotsUsed(Inventory inv) {
        int used = 0;
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty()) used++;
        }
        return used;
    }

    /** Number of empty slots in the 36-slot pickup-capable main inventory. */
    public static int mainSlotsFree(Inventory inv) {
        return inv.items.size() - mainSlotsUsed(inv);
    }

    /** Count all units of one item id in the 36-slot backpack. */
    public static int mainCount(
            Inventory inv,
            Item item) {
        return mainCount(inv.items, item);
    }

    static int mainCount(
            List<ItemStack> mainSlots,
            Item item) {
        if (item == null) return 0;
        int count = 0;
        for (ItemStack existing : mainSlots) {
            if (!existing.isEmpty() && existing.is(item)) {
                count += existing.getCount();
            }
        }
        return count;
    }

    /**
     * How many items matching {@code incoming} can still enter the normal
     * backpack. Armor and offhand slots deliberately do not count: vanilla
     * {@link Inventory#add(ItemStack)} does not use them for ground pickup.
     */
    public static int mainCapacityFor(
            Inventory inv,
            ItemStack incoming) {
        return mainCapacityFor(inv.items, incoming);
    }

    static int mainCapacityFor(
            List<ItemStack> mainSlots,
            ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        int capacity = 0;
        int max = incoming.getMaxStackSize();
        for (ItemStack existing : mainSlots) {
            if (existing.isEmpty()) {
                capacity += max;
            } else if (ItemStack.isSameItemSameTags(existing, incoming)) {
                capacity += Math.max(
                        0,
                        Math.min(existing.getMaxStackSize(), max)
                                - existing.getCount());
            }
        }
        return capacity;
    }

    /** Whether vanilla ground pickup can absorb at least one matching item. */
    public static boolean canAcceptMain(
            Inventory inv,
            ItemStack incoming) {
        return mainCapacityFor(inv, incoming) > 0;
    }
}
