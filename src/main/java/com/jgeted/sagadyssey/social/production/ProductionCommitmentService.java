package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;

import java.util.UUID;

/** 关闭计划的生产承诺；只更新账本，绝不再次消费物品或生成产物。 */
public final class ProductionCommitmentService {
    private ProductionCommitmentService() {}

    public static void close(UUID orderId, ProductionOrderData orders,
                             ProductionBatchData batches, ProductionMaterialRequestData requests,
                             RightsStateData rights, long now, String detail) {
        ProductionOrder order = orders.byId(orderId).orElse(null);
        if (order == null) return;
        ProductionBatch batch = batches.batch(order.batchId());
        if (batch != null && batch.status() == ProductionBatchStatus.COMPLETED) {
            if (!order.terminal()) {
                if (order.status() == ProductionOrderStatus.BLOCKED) {
                    order = order.resume(now, "关闭计划时恢复已完成批次的订单状态");
                    orders.put(order);
                }
                orders.put(order.transition(ProductionOrderStatus.COMPLETED, now,
                        "生产批次已经完成；计划关闭时补记订单终态"));
            }
        } else if (!order.terminal()) {
            orders.put(order.transition(ProductionOrderStatus.CANCELLED, now, detail));
        }
        ProductionService.cancel(batches, rights, order.batchId(), now, detail);
        for (ProductionMaterialRequest request : requests.forOrder(orderId)) {
            if (request.status() == ProductionMaterialRequest.Status.OPEN) {
                requests.cancel(request.idempotencyKey(), now, detail);
            }
        }
    }
}
