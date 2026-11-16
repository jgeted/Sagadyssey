package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCargoCapacity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WarCargoCapacityTest {
    @Test void anEmpty27SlotHoldCanCarry27RealStacksWithoutAnArtificialSmallLimit() {
        var hold = new SimpleContainer(27); var sample = new ItemStack(Items.BREAD);
        assertEquals(27L * sample.getMaxStackSize(), WarCargoCapacity.available(hold, sample, 27));
        assertEquals(9L * sample.getMaxStackSize(), WarCargoCapacity.available(hold, sample, 9));
        assertEquals(0, WarCargoCapacity.available(hold, sample, 0));
    }
    @Test void existingCargoAndPartialStacksConsumeActualSpaceWithoutMutatingThem() {
        var hold = new SimpleContainer(3);
        hold.setItem(0, new ItemStack(Items.BREAD, 60)); hold.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(68, WarCargoCapacity.available(hold, new ItemStack(Items.BREAD), 3));
        assertEquals(60, hold.getItem(0).getCount()); assertEquals(64, hold.getItem(1).getCount()); assertTrue(hold.getItem(2).isEmpty());
    }
    @Test void perItemAndPerComponentStackLimitsAreRespected() {
        var hold = new SimpleContainer(2);
        assertEquals(32, WarCargoCapacity.available(hold, new ItemStack(Items.ENDER_PEARL), 2));
        assertEquals(2, WarCargoCapacity.available(hold, new ItemStack(Items.IRON_SWORD), 2));
        var custom = new ItemStack(Items.BREAD); custom.set(DataComponents.MAX_STACK_SIZE, 8);
        assertEquals(16, WarCargoCapacity.available(hold, custom, 2));
        hold.setItem(0, new ItemStack(Items.BREAD, 4));
        assertEquals(8, WarCargoCapacity.available(hold, custom, 2));
    }
    @Test void slotsAndContainerRulesAreNotBypassed() {
        var hold = new SimpleContainer(2) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == 0; }
            @Override public int getMaxStackSize() { return 16; }
        };
        assertEquals(16, WarCargoCapacity.available(hold, new ItemStack(Items.BREAD), 2));
        assertThrows(IllegalArgumentException.class, () -> WarCargoCapacity.available(hold, ItemStack.EMPTY, 2));
        assertThrows(IllegalArgumentException.class, () -> WarCargoCapacity.available(hold, new ItemStack(Items.BREAD), 3));
    }
}
