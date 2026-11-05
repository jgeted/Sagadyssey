package com.jgeted.sagadyssey.social.production;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.social.industry.common.*;import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.production.*;import com.jgeted.sagadyssey.worldsim.rights.*;import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class DispatchOwnershipTest {
 private ProductionOrder order(String recipe){var owner=SubjectRef.character(UUID.randomUUID());return ProductionOrder.open(UUID.randomUUID(),owner,owner,recipe,List.of(new ProductionMaterial(new ItemFingerprint("minecraft:iron_ingot",0),2)),new ProductionMaterial(new ItemFingerprint("minecraft:shears",0),1),80,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,1,"dispatcher boundary");}
 @Test void namedWorkshopAndNativeRecipesCannotBeStolenEvenIfMalformed(){for(String recipe:List.of(WorkshopTask.BED,WorkshopTask.PLANKS,WorkshopTask.STICKS,FurnaceWorkAdapter.RECIPE,FurnaceWorkAdapter.BOUND_IRON,FurnaceWorkAdapter.BOUND_POTATO))assertFalse(NpcBrainRuntime.blacksmithDispatchable(order(recipe)),recipe);}
 @Test void ordinaryForgeRecipesRemainAvailableSubjectToExistingAuthority(){for(String recipe:List.of("sagadyssey:iron_pickaxe","minecraft:iron_axe","minecraft:iron_hoe","minecraft:shears","existing:legacy_recipe"))assertTrue(NpcBrainRuntime.blacksmithDispatchable(order(recipe)),recipe);}
 @Test void assignedNamedOrderDoesNotBecomeEligibleOnReload(){var original=order(WorkshopTask.BED).claim(UUID.randomUUID(),2,"old assignment");var restored=ProductionOrder.load(original.save());assertFalse(NpcBrainRuntime.blacksmithDispatchable(restored));}
}