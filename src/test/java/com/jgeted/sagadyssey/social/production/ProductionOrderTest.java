package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionOrderTest {
    @Test
    void nbtRoundTripPreservesBlockedOrderAndPhysicalLocators() {
        UUID workerId = UUID.randomUUID();
        ProductionOrder order = open(10L)
                .claim(workerId, "minecraft:overworld", new BlockPos(12, 70, -4),
                        11L, "铁匠认领")
                .transition(ProductionOrderStatus.RESERVED, 12L, "原料已预留")
                .block(new ProductionOrderBlocker("MATERIAL_MISSING", "还缺一个煤炭", 40L),
                        13L, "等待补料");

        ProductionOrder loaded = ProductionOrder.load(order.save());

        assertEquals(order, loaded);
        assertEquals(ProductionOrderStatus.RESERVED, loaded.blockedFromStatus());
        assertEquals(new BlockPos(12, 70, -4), loaded.workstationPosition());
        assertEquals("minecraft:overworld", loaded.targetContainerDimension());
        assertEquals(200, loaded.requiredWorkTicks());
    }

    @Test
    void rejectsInvalidLaborRequirementAtOrderBoundary() {
        ProductionOrder valid = open(10L);
        assertThrows(IllegalArgumentException.class, () -> new ProductionOrder(
                valid.id(), valid.issuer(), valid.outputOwner(), valid.recipeId(),
                valid.inputs(), valid.output(), 0, valid.batchId(), null,
                valid.workstationDimension(), valid.workstationPosition(),
                valid.targetContainerId(), valid.targetContainerDimension(),
                valid.targetContainerPosition(), ProductionOrderStatus.OPEN,
                null, null, valid.createdAt(), valid.updatedAt(), valid.detail()));
    }

    @Test
    void lifecycleRejectsSkippedBackwardAndTerminalTransitions() {
        ProductionOrder open = open(10L);

        assertThrows(IllegalStateException.class,
                () -> open.transition(ProductionOrderStatus.IN_PROGRESS, 11L, "跳步"));
        assertThrows(IllegalArgumentException.class,
                () -> open.transition(ProductionOrderStatus.CLAIMED, 11L, "缺少工人"));

        ProductionOrder completed = open.claim(UUID.randomUUID(), 11L, "认领")
                .transition(ProductionOrderStatus.RESERVED, 12L, "预留")
                .transition(ProductionOrderStatus.IN_PROGRESS, 13L, "生产")
                .transition(ProductionOrderStatus.SETTLING, 14L, "结算")
                .transition(ProductionOrderStatus.COMPLETED, 15L, "完成");
        assertTrue(completed.terminal());
        assertThrows(IllegalStateException.class,
                () -> completed.transition(ProductionOrderStatus.CANCELLED, 16L, "反向取消"));
    }

    @Test
    void blockAndResumePreserveOrderBatchAndAssignmentIdentity() {
        UUID workerId = UUID.randomUUID();
        ProductionOrder reserved = open(10L).claim(workerId, 11L, "认领")
                .transition(ProductionOrderStatus.RESERVED, 12L, "预留完成");
        ProductionOrder blocked = reserved.block(
                new ProductionOrderBlocker("WORKSTATION_BUSY", "铁砧被占用", 30L),
                13L, "等待铁砧");
        ProductionOrder resumed = blocked.resume(30L, "重新取得铁砧");

        assertEquals(ProductionOrderStatus.RESERVED, resumed.status());
        assertEquals(reserved.id(), resumed.id());
        assertEquals(reserved.batchId(), resumed.batchId());
        assertEquals(workerId, resumed.assignedWorkerId());
        assertEquals(reserved.inputs(), resumed.inputs());
        assertEquals(reserved.output(), resumed.output());
        assertEquals(reserved.targetContainerId(), resumed.targetContainerId());
        assertEquals(null, resumed.blocker());
        assertEquals(null, resumed.blockedFromStatus());
    }

    static ProductionOrder open(long now) {
        return ProductionOrder.open(UUID.randomUUID(),
                SubjectRef.institution(UUID.randomUUID()),
                SubjectRef.household(UUID.randomUUID()), "sagadyssey:iron_pickaxe",
                List.of(new ProductionMaterial(
                                new ItemFingerprint("minecraft:iron_ingot", 0), 3),
                        new ProductionMaterial(
                                new ItemFingerprint("minecraft:stick", 0), 2)),
                new ProductionMaterial(new ItemFingerprint("minecraft:iron_pickaxe", 0), 1),
                200, UUID.randomUUID(), null, null, UUID.randomUUID(),
                "minecraft:overworld", new BlockPos(20, 65, 8), now, "等待工人");
    }
}
