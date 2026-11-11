package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionOrderDataTest {
    @Test
    void dataRoundTripKeepsOpenAndAssignedQueriesReadOnly() {
        ProductionOrderData data = new ProductionOrderData();
        ProductionOrder open = ProductionOrderTest.open(10L);
        ProductionOrder claimedSource = ProductionOrderTest.open(20L);
        UUID workerId = UUID.randomUUID();
        ProductionOrder claimed = claimedSource.claim(workerId, 21L, "已认领");
        data.put(open);
        data.put(claimedSource);
        data.put(claimed);

        ProductionOrderData loaded = roundTrip(data);

        assertEquals(open, loaded.byId(open.id()).orElseThrow());
        assertEquals(claimed, loaded.byId(claimed.id()).orElseThrow());
        assertEquals(1, loaded.openOrders().size());
        assertEquals(claimed.id(), loaded.assignedTo(workerId).getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> loaded.openOrders().clear());
    }

    @Test
    void putRejectsIdentityRewriteAndHalfLifecycleSnapshot() {
        ProductionOrderData data = new ProductionOrderData();
        ProductionOrder open = ProductionOrderTest.open(10L);
        data.put(open);

        ProductionOrder rewrittenBatch = new ProductionOrder(open.id(), open.issuer(),
                open.outputOwner(), open.recipeId(), open.inputs(), open.output(),
                open.requiredWorkTicks(), UUID.randomUUID(), null,
                open.workstationDimension(), open.workstationPosition(),
                open.targetContainerId(), open.targetContainerDimension(),
                open.targetContainerPosition(), ProductionOrderStatus.OPEN,
                null, null, open.createdAt(), 11L, "篡改批次");
        assertThrows(IllegalStateException.class, () -> data.put(rewrittenBatch));

        ProductionOrder claimed = open.claim(UUID.randomUUID(), 11L, "认领");
        ProductionOrderData empty = new ProductionOrderData();
        assertThrows(IllegalStateException.class, () -> empty.put(claimed));
    }

    @Test
    void malformedRecordsAreSkippedIndividually() {
        ProductionOrderData source = new ProductionOrderData();
        ProductionOrder valid = ProductionOrderTest.open(10L);
        source.put(valid);
        CompoundTag tag = source.save(new CompoundTag(), null);
        MinecraftApi.listOfType(tag, "Orders", Tag.TAG_COMPOUND).add(new CompoundTag());

        ProductionOrderData loaded = ProductionOrderData.load(tag, null);

        assertEquals(valid, loaded.byId(valid.id()).orElseThrow());
        assertEquals(1, loaded.size());
    }

    @Test
    void capacityPrunesOnlyOldestTerminalOrder() {
        ProductionOrderData data = new ProductionOrderData();
        ProductionOrder terminalSource = ProductionOrderTest.open(1L);
        data.put(terminalSource);
        ProductionOrder terminal = terminalSource.claim(UUID.randomUUID(), 2L, "认领")
                .transition(ProductionOrderStatus.RESERVED, 3L, "预留")
                .transition(ProductionOrderStatus.IN_PROGRESS, 4L, "生产")
                .transition(ProductionOrderStatus.SETTLING, 5L, "结算")
                .transition(ProductionOrderStatus.COMPLETED, 6L, "完成");
        data.put(terminalSource.claim(terminal.assignedWorkerId(), 2L, "认领"));
        data.put(data.byId(terminal.id()).orElseThrow()
                .transition(ProductionOrderStatus.RESERVED, 3L, "预留"));
        data.put(data.byId(terminal.id()).orElseThrow()
                .transition(ProductionOrderStatus.IN_PROGRESS, 4L, "生产"));
        data.put(data.byId(terminal.id()).orElseThrow()
                .transition(ProductionOrderStatus.SETTLING, 5L, "结算"));
        data.put(data.byId(terminal.id()).orElseThrow()
                .transition(ProductionOrderStatus.COMPLETED, 6L, "完成"));

        for (int index = 1; index < ProductionOrderData.MAX_ORDERS; index++) {
            data.put(ProductionOrderTest.open(10L + index));
        }
        assertEquals(ProductionOrderData.MAX_ORDERS, data.size());
        ProductionOrder newest = ProductionOrderTest.open(10_000L);
        data.put(newest);

        assertEquals(ProductionOrderData.MAX_ORDERS, data.size());
        assertTrue(data.byId(terminal.id()).isEmpty());
        assertEquals(newest, data.byId(newest.id()).orElseThrow());
        assertEquals(ProductionOrderData.MAX_ORDERS, data.openOrders().size());
    }

    @Test
    void fullActiveLedgerRejectsInsertionInsteadOfDroppingAnActiveOrder() {
        ProductionOrderData data = new ProductionOrderData();
        for (int index = 0; index < ProductionOrderData.MAX_ORDERS; index++) {
            data.put(ProductionOrderTest.open(index));
        }
        ProductionOrder extra = ProductionOrderTest.open(3_000L);

        assertThrows(IllegalStateException.class, () -> data.put(extra));
        assertFalse(data.byId(extra.id()).isPresent());
        assertEquals(ProductionOrderData.MAX_ORDERS, data.size());
    }

    private static ProductionOrderData roundTrip(ProductionOrderData data) {
        CompoundTag tag = data.save(new CompoundTag(), null);
        assertEquals(ProductionOrderData.SCHEMA_VERSION, tag.getIntOr("Schema", 0));
        return ProductionOrderData.load(tag, null);
    }
}
