package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcExecutionContext;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationAcquireResult;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderBlocker;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/** 铁匠四个执行器共享的严格解析、订单与互斥预留逻辑。 */
final class BlacksmithExecutionSupport {
    private BlacksmithExecutionSupport() {
    }

    static NpcEntityExecutionContext entityContext(NpcExecutionContext context) {
        if (!(context instanceof NpcEntityExecutionContext entityContext)) {
            throw new IllegalArgumentException("铁匠任务需要服务端 NPC 实体上下文");
        }
        return entityContext;
    }

    static UUID requiredUuid(NpcPlanStep step, String key) {
        String value = step.params().get(key);
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无效的铁匠任务参数: " + key, exception);
        }
    }

    static int requiredWorkTicks(NpcPlanStep step) {
        String value = step.params().get(BlacksmithTaskIds.REQUIRED_WORK_TICKS);
        try {
            int ticks = Integer.parseInt(value);
            if (ticks < 1 || ticks > 72_000) throw new NumberFormatException("outside range");
            return ticks;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无效的铁匠劳动时长", exception);
        }
    }

    static ProductionOrder requireOrder(NpcEntityExecutionContext context, NpcPlanStep step) {
        UUID orderId = requiredUuid(step, BlacksmithTaskIds.ORDER_ID);
        UUID batchId = requiredUuid(step, BlacksmithTaskIds.BATCH_ID);
        ProductionOrder order = ProductionOrderData.get(context.level().getServer())
                .byId(orderId).orElseThrow(() -> new IllegalStateException("生产订单不存在"));
        if (!order.batchId().equals(batchId)) {
            throw new IllegalStateException("计划批次与生产订单不一致");
        }
        if (order.assignedWorkerId() != null
                && !context.npc().getCharacterId().equals(order.assignedWorkerId())) {
            throw new IllegalStateException("生产订单不属于当前铁匠");
        }
        return order;
    }

    static ProductionOrder resumeIfBlocked(NpcEntityExecutionContext context,
                                           ProductionOrder order, long now) {
        if (order.status() != ProductionOrderStatus.BLOCKED) return order;
        ProductionOrder resumed = order.resume(now, "阻塞条件到期，重新核验");
        ProductionOrderData.get(context.level().getServer()).put(resumed);
        return resumed;
    }

    static com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult block(
            NpcEntityExecutionContext context, ProductionOrder order, String code,
            String detail, long retryAt) {
        ProductionOrder blocked = order.status() == ProductionOrderStatus.BLOCKED
                ? order
                : order.block(new ProductionOrderBlocker(code, detail, retryAt),
                context.gameTime(), detail);
        if (blocked != order) {
            ProductionOrderData.get(context.level().getServer()).put(blocked);
        }
        return com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult.blocked(detail,
                new NpcPlanBlocker(code, detail, retryAt, order.id().toString()));
    }

    static ReservationCheck reserveEphemeral(NpcEntityExecutionContext context, NpcPlan plan,
                                             ProductionOrder order,
                                             NpcReservationService reservations) {
        long now = context.gameTime();
        long expiresAt = Math.max(now + 200L, plan.expiresAt());
        NpcReservationAcquireResult workstation = reservations.acquire(
                workstationKey(order), plan.id(), context.npcId(), now, expiresAt);
        if (!workstation.acquired()) {
            return new ReservationCheck(false, "WORKSTATION_BUSY", "工作站正被另一计划使用");
        }
        NpcReservationAcquireResult output = reservations.acquire(
                outputKey(order), plan.id(), context.npcId(), now, expiresAt);
        if (!output.acquired()) {
            reservations.releasePlan(plan.id());
            return new ReservationCheck(false, "OUTPUT_BUSY", "成品容器正由另一生产计划结算");
        }
        return new ReservationCheck(true, "", "");
    }

    static NpcReservationKey workstationKey(ProductionOrder order) {
        if (order.workstationDimension() == null || order.workstationPosition() == null) {
            throw new IllegalStateException("订单尚未指定工作站");
        }
        return new NpcReservationKey("workstation",
                order.workstationDimension() + ':' + order.workstationPosition().asLong());
    }

    static NpcReservationKey outputKey(ProductionOrder order) {
        return new NpcReservationKey("production_output", order.targetContainerId().toString());
    }

    static Container targetContainer(NpcEntityExecutionContext context, ProductionOrder order) {
        String currentDimension = context.level().dimension().identifier().toString();
        if (!currentDimension.equals(order.targetContainerDimension())) {
            throw new IllegalStateException("成品容器不在当前维度");
        }
        if (!ProductionContainerIds.resolve(context.level().getServer(), currentDimension,
                order.targetContainerPosition()).equals(order.targetContainerId())) {
            throw new IllegalStateException("订单容器身份与物理位置不一致");
        }
        if (!context.level().hasChunkAt(order.targetContainerPosition())) return null;
        return com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(
                context.level().getBlockEntity(order.targetContainerPosition()));
    }

    static boolean workstationAvailable(NpcEntityExecutionContext context,
                                        ProductionOrder order) {
        if (order.workstationDimension() == null || order.workstationPosition() == null
                || !context.level().dimension().identifier().toString()
                .equals(order.workstationDimension())
                || !context.level().hasChunkAt(order.workstationPosition())) {
            return false;
        }
        var block = context.level().getBlockState(order.workstationPosition()).getBlock();
        return block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL || block == Blocks.SMITHING_TABLE;
    }

    static boolean atWorkstation(NpcEntityExecutionContext context, ProductionOrder order) {
        return workstationAvailable(context, order)
                && context.npc().distanceToSqr(order.workstationPosition().getX() + 0.5D,
                order.workstationPosition().getY() + 0.5D,
                order.workstationPosition().getZ() + 0.5D) <= 9.0D;
    }

    static boolean hasEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) return true;
        }
        return false;
    }

    record ReservationCheck(boolean successful, String code, String detail) {
    }
}
