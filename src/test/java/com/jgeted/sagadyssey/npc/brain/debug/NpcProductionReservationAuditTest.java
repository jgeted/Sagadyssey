package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcProductionReservationAuditTest {
    @Test
    void completedBatchReservationsRemainVisibleWithoutAnOrderOrActivePlan() {
        UUID worker = UUID.randomUUID();
        ProductionBatch batch = batch(worker).finish(ProductionBatchStatus.COMPLETED, 20L, "完成");
        AssetReservation stale = reservation(batch.id());
        List<AssetReservation> source = new ArrayList<>(List.of(stale, reservation(UUID.randomUUID())));

        var result = NpcProductionReservationAudit.capture(worker, List.of(), List.of(batch), source);

        assertEquals(List.of(stale), result);
        assertEquals(2, source.size());
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertTrue(NpcProductionReservationAudit.capture(UUID.randomUUID(), List.of(),
                List.of(batch), source).isEmpty());
    }

    @Test
    void orderIdentifiesReservationsEvenWhenItsBatchIsMissing() {
        UUID worker = UUID.randomUUID();
        ProductionBatch batch = batch(worker);
        ProductionOrder order = ProductionOrder.open(UUID.randomUUID(), batch.outputOwner(),
                batch.outputOwner(), batch.recipeId(), batch.inputs(), batch.output(),
                80, batch.id(), "minecraft:overworld", new BlockPos(2, 64, 0),
                batch.containerId(), "minecraft:overworld", new BlockPos(4, 64, 0), 1L, "")
                .claim(worker, 2L, "认领").transition(ProductionOrderStatus.CANCELLED, 3L, "到期");
        AssetReservation stale = reservation(batch.id());

        assertEquals(List.of(stale), NpcProductionReservationAudit.capture(worker,
                List.of(order), List.of(), List.of(stale)));
    }

    private static ProductionBatch batch(UUID worker) {
        SubjectRef owner = SubjectRef.character(worker);
        ProductionMaterial material = new ProductionMaterial(new ItemFingerprint("minecraft:iron_ingot", 0), 3);
        return new ProductionBatch(UUID.randomUUID(), "sagadyssey:test_iron_pickaxe", worker,
                owner, UUID.randomUUID(), List.of(material), material, 80,
                1L, 0L, ProductionBatchStatus.RESERVED, "");
    }

    private static AssetReservation reservation(UUID batchId) {
        return new AssetReservation(UUID.randomUUID(), UUID.randomUUID(),
                new ItemFingerprint("minecraft:iron_ingot", 0), 3,
                SubjectRef.character(UUID.randomUUID()), "production:" + batchId,
                "旧记录", 1L, 10L);
    }
}
