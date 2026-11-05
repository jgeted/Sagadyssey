package com.jgeted.sagadyssey.social.industry;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeFoodContractTest {
    @Test void codHasOnlyApprovedFiniteFoodValue(){var cod=new ItemStack(Items.COD);assertEquals(1,NpcFoodItems.rationValue(cod));assertEquals(160,NpcFoodItems.hungerRestore(cod));}
    @Test void ordinaryDietDoesNotConsumeRawCod(){assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.COD),false));assertTrue(NpcFoodItems.canConsumePlanned(new ItemStack(Items.COD),true));}
    @Test void ordinaryFoodStillPreferredAndQueryDoesNotEat(){var bag=new SimpleContainer(new ItemStack(Items.COD,2),new ItemStack(Items.BREAD));assertEquals(1,NpcFoodItems.findPlannedFoodSlot(bag,true));assertEquals(2,bag.getItem(0).getCount());assertEquals(1,bag.getItem(1).getCount());bag.setItem(1,ItemStack.EMPTY);assertEquals(-1,NpcFoodItems.findPlannedFoodSlot(bag,false));assertEquals(0,NpcFoodItems.findPlannedFoodSlot(bag,true));assertEquals(2,bag.getItem(0).getCount());}
    @Test void noOtherDietOrVesselSupportIsInferred(){assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.COOKED_COD),true));assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.HONEY_BOTTLE),true));assertFalse(NpcFoodItems.canConsumePlanned(new ItemStack(Items.ROTTEN_FLESH),true));}
}
