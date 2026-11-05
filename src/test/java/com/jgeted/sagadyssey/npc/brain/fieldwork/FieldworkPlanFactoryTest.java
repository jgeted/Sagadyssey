package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.brain.persistence.NpcPlanNbtCodec;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FieldworkPlanFactoryTest {
    @Test void harvestAndReplantAreTwoDistinctPersistedActionsBeforeDelivery() {
        var crop = new FieldworkTarget(new BlockPos(5, 1, 5), FieldworkAction.Operation.HARVEST,
                Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), Blocks.AIR.defaultBlockState(), null, null);
        var route = new FieldworkRoutes.Route(new BlockPos(4, 1, 5), new BlockPos(4, 1, 5), List.of(), ItemStack.EMPTY);
        var plan = FieldworkPlanFactory.work(UUID.randomUUID(), crop, route, "minecraft:overworld", 10);
        assertEquals(5, plan.steps().size());
        assertEquals("HARVEST", plan.steps().get(2).params().get("operation"));
        assertEquals("PLANT", plan.steps().get(3).params().get("operation"));
        assertEquals("minecraft:wheat_seeds", plan.steps().get(3).params().get("input"));
        assertEquals(FieldworkPlanFactory.DEPOSIT, plan.steps().getLast().executorId());
        assertEquals(plan, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(plan)));
    }
    @Test void allScaffoldBlocksHaveSeparatePlacementAndReverseRecoverySteps() {
        var scaffold = List.of(new BlockPos(2, 1, 5), new BlockPos(3, 1, 5), new BlockPos(3, 2, 5));
        var route = new FieldworkRoutes.Route(new BlockPos(3, 3, 5), new BlockPos(1, 1, 5), scaffold, new ItemStack(Items.DIRT, 3));
        var log = new FieldworkTarget(new BlockPos(5, 6, 5), FieldworkAction.Operation.CHOP,
                Blocks.OAK_LOG.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null);
        var plan = FieldworkPlanFactory.work(UUID.randomUUID(), log, route, "minecraft:overworld", 10);
        var placements = plan.steps().stream().filter(s -> "PLACE_SCAFFOLD".equals(s.params().get("operation"))).toList();
        var recoveries = plan.steps().stream().filter(s -> "RECOVER_SCAFFOLD".equals(s.params().get("operation"))).toList();
        assertEquals(scaffold, placements.stream().map(s -> FieldworkTarget.position(s.params())).toList());
        assertEquals(scaffold.reversed(), recoveries.stream().map(s -> FieldworkTarget.position(s.params())).toList());
        assertTrue(recoveries.stream().allMatch(s -> s.phase() == NpcPlanPhase.SETTLING));
        for (int i = 1; i < plan.steps().size(); i++) assertTrue(plan.steps().get(i).phase().canFollow(plan.steps().get(i - 1).phase()));
        assertEquals(plan, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(plan)));
    }
    @Test void cocoaReplantKeepsFacingAndConsumesOneRealBean() {
        var before = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2);
        var cocoa = new FieldworkTarget(new BlockPos(3, 1, 3), FieldworkAction.Operation.HARVEST, before, Blocks.AIR.defaultBlockState(), null, null);
        var route = new FieldworkRoutes.Route(BlockPos.ZERO, BlockPos.ZERO, List.of(), ItemStack.EMPTY);
        var plan = FieldworkPlanFactory.work(UUID.randomUUID(), cocoa, route, "minecraft:overworld", 10);
        assertEquals("minecraft:cocoa_beans", plan.steps().get(3).params().get("input"));
        assertTrue(plan.steps().get(3).params().get("result").contains("age:\"0\""));
    }
    @Test void restHasPersistedFiniteDeadlineAndRetainsPartialScanWhenRequested() {
        var plan = FieldworkPlanFactory.rest(UUID.randomUUID(), 10, 200, "暂时无成熟作物", true);
        assertEquals("210", plan.currentStep().params().get("until_game_time"));
        assertEquals("true", plan.currentStep().params().get("continue_scan"));
        assertEquals(plan, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(plan)));
    }
}
