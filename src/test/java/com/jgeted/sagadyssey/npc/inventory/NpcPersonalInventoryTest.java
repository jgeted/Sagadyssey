package com.jgeted.sagadyssey.npc.inventory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpcPersonalInventoryTest {
    @Test
    void hasTheSamePortableSlotCountAsAPlayer() {
        NpcPersonalInventory inventory = new NpcPersonalInventory();
        assertEquals(36, inventory.getContainerSize());
    }

    @Test
    void insertionMergesThenUsesEmptySlotsWithoutMutatingOffer() {
        NpcPersonalInventory inventory = new NpcPersonalInventory();
        inventory.setItem(0, new ItemStack(Items.ARROW, 60));
        ItemStack offered = new ItemStack(Items.ARROW, 10);

        ItemStack remainder = inventory.insert(offered);

        assertTrue(remainder.isEmpty());
        assertEquals(10, offered.getCount());
        assertEquals(70, inventory.count(stack -> stack.is(Items.ARROW)));
        assertEquals(64, inventory.getItem(0).getCount());
        assertEquals(6, inventory.getItem(1).getCount());
    }

    @Test
    void consumptionIsAtomicWhenQuantityIsInsufficient() {
        NpcPersonalInventory inventory = new NpcPersonalInventory();
        inventory.setItem(0, new ItemStack(Items.ARROW, 3));
        long beforeRevision = inventory.revision();

        assertFalse(inventory.consume(stack -> stack.is(Items.ARROW), 4));
        assertEquals(3, inventory.getItem(0).getCount());
        assertEquals(beforeRevision, inventory.revision());

        assertTrue(inventory.consume(stack -> stack.is(Items.ARROW), 2));
        assertEquals(1, inventory.getItem(0).getCount());
        assertTrue(inventory.revision() > beforeRevision);
    }

    @Test
    void legacySpecialSlotsNeverLoseItemsWhenInventoryIsFull() {
        NpcPersonalInventory inventory = new NpcPersonalInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        List<ItemStack> overflow = NpcInventoryMigration.mergeLegacySlots(inventory,
                List.of(new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 8)));

        assertEquals(2, overflow.size());
        assertEquals(1, overflow.get(0).getCount());
        assertEquals(8, overflow.get(1).getCount());
        assertEquals(36 * 64 + 9, NpcInventoryMigration.totalItems(inventory, overflow));
    }
}
