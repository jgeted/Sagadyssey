package com.jgeted.sagadyssey.social.production;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionMaterialRequestDataTest {
    @Test
    void openOrGetIsIdempotentAndRoundTrips() {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        UUID orderId = UUID.randomUUID();
        UUID containerId = UUID.randomUUID();
        ProductionMaterial material = ProductionMaterialRequestTest.material(
                "minecraft:iron_ingot", 17, 3);

        ProductionMaterialRequest first = data.openOrGet(
                orderId, containerId, material, 10L, "首次缺料");
        ProductionMaterialRequest repeated = data.openOrGet(
                orderId, containerId, material, 30L, "再次检测到缺料");

        assertEquals(first, repeated);
        assertEquals(1, data.size());
        ProductionMaterialRequestData loaded = roundTrip(data);
        assertEquals(first, loaded.byKey(first.idempotencyKey()).orElseThrow());
        assertEquals(first, loaded.forOrder(orderId).getFirst());
        assertThrows(UnsupportedOperationException.class, () -> loaded.openRequests().clear());
    }

    @Test
    void duplicateKeyWithDifferentFixedIdentityIsRejected() {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        UUID orderId = UUID.randomUUID();
        ProductionMaterial material = ProductionMaterialRequestTest.material(
                "minecraft:iron_ingot", 0, 3);
        data.openOrGet(orderId, UUID.randomUUID(), material, 10L, "原请求");

        assertThrows(IllegalStateException.class, () -> data.openOrGet(
                orderId, UUID.randomUUID(), material, 11L, "容器冲突"));
        ProductionMaterial changedQuantity = ProductionMaterialRequestTest.material(
                "minecraft:iron_ingot", 0, 4);
        assertThrows(IllegalStateException.class, () -> data.openOrGet(
                orderId, data.forOrder(orderId).getFirst().containerId(),
                changedQuantity, 11L, "数量冲突"));
        assertEquals(1, data.size());
    }

    @Test
    void fulfillAndCancelRejectMissingOrTerminalRequests() {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        ProductionMaterialRequest request = data.openOrGet(
                UUID.randomUUID(), UUID.randomUUID(),
                ProductionMaterialRequestTest.material("minecraft:coal", 0, 2),
                10L, "缺料");

        ProductionMaterialRequest fulfilled = data.fulfill(
                request.idempotencyKey(), 20L, "已交付");
        assertEquals(ProductionMaterialRequest.Status.FULFILLED, fulfilled.status());
        assertTrue(data.openRequests().isEmpty());
        assertThrows(IllegalStateException.class, () -> data.cancel(
                request.idempotencyKey(), 21L, "不能再取消"));
        assertThrows(java.util.NoSuchElementException.class,
                () -> data.fulfill("production-order:missing", 21L, "不存在"));
    }

    @Test
    void malformedRecordsAreSkippedIndividually() {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        ProductionMaterialRequest valid = data.openOrGet(
                UUID.randomUUID(), UUID.randomUUID(),
                ProductionMaterialRequestTest.material("minecraft:iron_ingot", 0, 3),
                10L, "有效请求");
        CompoundTag tag = data.save(new CompoundTag(), null);
        tag.getList("Requests", Tag.TAG_COMPOUND).add(new CompoundTag());

        ProductionMaterialRequestData loaded = ProductionMaterialRequestData.load(tag, null);

        assertEquals(1, loaded.size());
        assertEquals(valid, loaded.byKey(valid.idempotencyKey()).orElseThrow());
    }

    @Test
    void capacityPrunesOnlyOldestTerminalAndRejectsAllActiveOverflow() {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        ProductionMaterialRequest oldest = data.openOrGet(
                UUID.randomUUID(), UUID.randomUUID(),
                ProductionMaterialRequestTest.material("minecraft:coal", 0, 1),
                1L, "将被淘汰");
        data.fulfill(oldest.idempotencyKey(), 2L, "完成");
        for (int index = 1; index < ProductionMaterialRequestData.MAX_REQUESTS; index++) {
            data.openOrGet(UUID.randomUUID(), UUID.randomUUID(),
                    ProductionMaterialRequestTest.material("minecraft:iron_ingot", 0, 1),
                    10L + index, "活动请求");
        }

        ProductionMaterialRequest newest = data.openOrGet(
                UUID.randomUUID(), UUID.randomUUID(),
                ProductionMaterialRequestTest.material("minecraft:gold_ingot", 0, 1),
                10_000L, "新请求");
        assertEquals(ProductionMaterialRequestData.MAX_REQUESTS, data.size());
        assertFalse(data.byKey(oldest.idempotencyKey()).isPresent());
        assertEquals(newest, data.byKey(newest.idempotencyKey()).orElseThrow());

        ProductionMaterialRequest extra = ProductionMaterialRequest.open(
                UUID.randomUUID(), UUID.randomUUID(),
                ProductionMaterialRequestTest.material("minecraft:copper_ingot", 0, 1),
                20_000L, "无终态可淘汰");
        assertThrows(IllegalStateException.class, () -> data.openOrGet(
                extra.orderId(), extra.containerId(), extra.material(),
                extra.createdAt(), extra.detail()));
        assertFalse(data.byKey(extra.idempotencyKey()).isPresent());
    }

    private static ProductionMaterialRequestData roundTrip(ProductionMaterialRequestData data) {
        CompoundTag tag = data.save(new CompoundTag(), null);
        assertEquals(ProductionMaterialRequestData.SCHEMA_VERSION, tag.getInt("Schema"));
        return ProductionMaterialRequestData.load(tag, null);
    }
}
