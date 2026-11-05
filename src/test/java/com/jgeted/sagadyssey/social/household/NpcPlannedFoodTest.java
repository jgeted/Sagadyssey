package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NpcPlannedFoodTest {
    @Test void ordinaryFoodExcludesVesselsAndUnsupportedFood() {
        assertTrue(NpcFoodItems.canConsumePlanned(new ItemStack(Items.APPLE),false));
        assertTrue(NpcFoodItems.canConsumePlanned(new ItemStack(Items.BREAD),false));
        assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.HONEY_BOTTLE),false));
        assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.ROTTEN_FLESH),false));
        assertFalse(NpcFoodItems.canConsumePlanned(ItemStack.EMPTY,true));
    }
    @Test void emergencyFoodRemainsEmergencyOnlyWithoutOpeningVesselConsumption() {
        for(var item:new net.minecraft.world.item.Item[]{Items.CARROT,Items.POTATO,Items.BEETROOT}) {
            assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(item),false));
            assertTrue(NpcFoodItems.canConsumePlanned(new ItemStack(item),true));
        }
        assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.HONEY_BOTTLE),true));
    }
    @Test void plannedSelectionMatchesExecutorAndLeavesLegacyFinderUnchanged() {
        var bag=new SimpleContainer(new ItemStack(Items.HONEY_BOTTLE),new ItemStack(Items.CARROT),new ItemStack(Items.APPLE));
        assertEquals(2,NpcFoodItems.findPlannedFoodSlot(bag,false));
        assertEquals(2,NpcFoodItems.findPlannedFoodSlot(bag,true));
        bag.setItem(2,ItemStack.EMPTY);
        assertEquals(-1,NpcFoodItems.findPlannedFoodSlot(bag,false));
        assertEquals(1,NpcFoodItems.findPlannedFoodSlot(bag,true));
        assertEquals(0,NpcFoodItems.findFoodSlot(bag,false));
    }
    @Test void householdCountsOrdinaryPiecesNotNutritionOrEmergencyStock() {
        var pantry=new SimpleContainer(new ItemStack(Items.APPLE,2),new ItemStack(Items.BREAD),
                new ItemStack(Items.HONEY_BOTTLE,6),new ItemStack(Items.CARROT,6),new ItemStack(Items.ROTTEN_FLESH,6));
        var snapshot=new HouseholdProvisionPlan(UUID.randomUUID(),6,0,0).inspect(pantry);
        assertEquals(3,snapshot.food());assertEquals(3,snapshot.foodDeficit());
        assertEquals(260,NpcFoodItems.hungerRestore(new ItemStack(Items.APPLE)));
        assertEquals(400,NpcFoodItems.hungerRestore(new ItemStack(Items.BREAD)));
    }
}
