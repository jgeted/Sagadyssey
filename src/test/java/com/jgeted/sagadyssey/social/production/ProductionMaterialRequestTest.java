package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionMaterialRequestTest {
    @Test
    void stableKeyAndNbtRoundTripKeepExactIdentity() {
        UUID orderId = UUID.randomUUID();
        UUID containerId = UUID.randomUUID();
        ProductionMaterial material = material("minecraft:iron_ingot", -42, 3);
        ProductionMaterialRequest request = ProductionMaterialRequest.open(
                orderId, containerId, material, 10L, "等待三块铁锭");

        assertEquals("production-order:" + orderId + ":minecraft:iron_ingot:-42",
                request.idempotencyKey());
        assertEquals(request, ProductionMaterialRequest.load(request.save()));
    }

    @Test
    void terminalTransitionsAreStrictAndPreserveIdentity() {
        ProductionMaterialRequest open = ProductionMaterialRequest.open(
                UUID.randomUUID(), UUID.randomUUID(),
                material("minecraft:coal", 0, 2), 10L, "缺少燃料");
        ProductionMaterialRequest fulfilled = open.fulfill(20L, "已送达");

        assertEquals(ProductionMaterialRequest.Status.FULFILLED, fulfilled.status());
        assertTrue(fulfilled.terminal());
        assertEquals(open.idempotencyKey(), fulfilled.idempotencyKey());
        assertEquals(open.createdAt(), fulfilled.createdAt());
        assertThrows(IllegalStateException.class,
                () -> fulfilled.cancel(21L, "不能反向取消"));
        assertThrows(IllegalArgumentException.class,
                () -> open.cancel(9L, "时间倒退"));
    }

    static ProductionMaterial material(String itemId, int componentsHash, int quantity) {
        return new ProductionMaterial(new ItemFingerprint(itemId, componentsHash), quantity);
    }
}
