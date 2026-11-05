package com.jgeted.sagadyssey.npc.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Pure migration helper used by entity loading and executable conservation tests. */
public final class NpcInventoryMigration {
    public static final int DATA_VERSION = 2;

    private NpcInventoryMigration() {}

    /** Moves legacy special-slot contents into the normal inventory without deleting remainders. */
    public static List<ItemStack> mergeLegacySlots(NpcPersonalInventory inventory,
                                                   Collection<ItemStack> legacyStacks) {
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack stack : legacyStacks) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack remainder = inventory.insert(stack);
            if (!remainder.isEmpty()) overflow.add(remainder.copy());
        }
        return List.copyOf(overflow);
    }

    public static int totalItems(NpcPersonalInventory inventory, Collection<ItemStack> overflow) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += inventory.getItem(slot).getCount();
        }
        for (ItemStack stack : overflow) total += stack.getCount();
        return total;
    }
}
