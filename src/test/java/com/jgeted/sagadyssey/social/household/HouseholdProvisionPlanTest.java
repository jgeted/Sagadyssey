package com.jgeted.sagadyssey.social.household;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HouseholdProvisionPlanTest {
    @Test
    void shortagesComeFromPhysicalFoodFuelAndUsableTools() {
        HouseholdProvisionPlan plan = new HouseholdProvisionPlan(UUID.randomUUID(), 12, 8, 2);
        SimpleContainer pantry = new SimpleContainer(6);
        pantry.setItem(0, new ItemStack(Items.BREAD, 7));
        pantry.setItem(1, new ItemStack(Items.COAL, 3));
        pantry.setItem(2, new ItemStack(Items.IRON_HOE));

        HouseholdProvisionPlan.Snapshot snapshot = plan.inspect(pantry);

        assertEquals(5, snapshot.foodDeficit());
        assertEquals(5, snapshot.fuelDeficit());
        assertEquals(1, snapshot.toolDeficit());
        assertFalse(snapshot.secure());
    }
}
