package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderRecord;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightGrant;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.LimitedContainerView;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 运输员和商人在 WORK 状态下执行实体运输订单。全程操作真实容器和 NPC 独立货舱。 */
public final class NpcLogisticsGoal extends Goal {
    private static final double INTERACTION_RANGE_SQ = 9.0D;
    private static final int RETRY_TICKS = 40;

    private final NpcBase npc;
    @Nullable private UUID orderId;
    @Nullable private BlockPos warehouseApproach;
    private Stage stage = Stage.TO_SOURCE;
    private int recalcPath;
    private int retryCooldown;
    private int pathFailures;
    private boolean finished;
    private final MovementProgress movementProgress = new MovementProgress();

    private enum Stage { TO_SOURCE, TO_DESTINATION }

    public NpcLogisticsGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (npc.level().isClientSide() || !npc.isAlive()) return false;
        if (!isTransportProfession() || npc.getCommand() != NpcCommand.WORK
                || npc.getTransportSlotLimit() <= 0
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        NpcWorkContext workContext = NpcWorkContext.from(npc);
        if (npc.getTarget() != null || !workContext.hasEmployment()) return false;
        if ((npc.tickCount - npc.getId()) % 20 != 0) return false;
        ServerLevel level = (ServerLevel) npc.level();
        LogisticsData data = LogisticsData.get(level.getServer());
        LogisticsOrderRecord order = data.claimNextOrder(npc.getCharacterId(), workContext,
                level.dimension().identifier().toString(), level.getGameTime());
        if (order == null) {
            npc.reportWorkActivity("物流等待", null,
                    workContext.describe() + "；当前没有可领取订单");
            return false;
        }
        if (order.inTransitCount() > 0 && cargoCount(order.itemId()) <= 0) {
            data.releaseOrdersForNpc(npc.getCharacterId(), level.getGameTime());
            return false;
        }
        if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.WORK,
                order.createdByCharacter(), 50.0D, order.inTransitCount() > 0 ? 6.0D : 3.0D,
                false)) {
            data.releaseOrdersForNpc(npc.getCharacterId(), level.getGameTime());
            return false;
        }
        orderId = order.id();
        stage = order.inTransitCount() > 0 ? Stage.TO_DESTINATION : Stage.TO_SOURCE;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (orderId == null || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null
                || !isTransportProfession() || !npc.isAlive() || finished
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        LogisticsOrderRecord order = LogisticsData.get(((ServerLevel) npc.level()).getServer()).order(orderId);
        return order != null && order.status() == LogisticsOrderStatus.IN_PROGRESS
                && npc.getCharacterId().equals(order.assignedNpcId());
    }

    @Override
    public void start() {
        recalcPath = 0;
        retryCooldown = 0;
        pathFailures = 0;
        finished = false;
        refreshClientStatus();
        moveTowardCurrentWarehouse();
        reportStage("开始执行订单");
    }

    @Override
    public void tick() {
        if (orderId == null) return;
        ServerLevel level = (ServerLevel) npc.level();
        LogisticsData data = LogisticsData.get(level.getServer());
        LogisticsOrderRecord order = data.order(orderId);
        if (order == null) return;
        data.touchOrder(order.id(), npc.getCharacterId(), level.getGameTime());
        WarehouseRecord warehouse = data.warehouse(stage == Stage.TO_SOURCE
                ? order.sourceWarehouseId() : order.destinationWarehouseId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().identifier().toString())) {
            finished = true;
            return;
        }

        int carried = cargoCount(order.itemId());
        npc.syncLogisticsStatus(stage == Stage.TO_SOURCE ? 1 : 2, order.itemId(),
                order.deliveredCount(), order.requestedCount(), carried);
        if ((npc.tickCount - npc.getId()) % 20 == 0) {
            showRouteFeedback(level, warehouse.position());
        }

        BlockPos pos = warehouse.position();
        npc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (!InteractionApproach.readyToInteract(
                npc, pos, warehouseApproach, INTERACTION_RANGE_SQ)) {
            if (warehouseApproach == null) {
                warehouseApproach = InteractionApproach.find(npc, pos);
                if (warehouseApproach == null) {
                    finished = true;
                    return;
                }
                movementProgress.reset(npc, warehouseApproach);
            }
            if (movementProgress.tick(npc, warehouseApproach)) {
                pathFailures++;
                if (pathFailures == 3 && npc.isPassenger()) {
                    npc.dismountToBind();
                    npc.setLeadMountMode(true);
                    reportStage("坐骑路线受阻，改为牵马步行");
                } else if (pathFailures >= 6) {
                    reportStage("连续寻路失败，释放移动控制后重试");
                    finished = true;
                    return;
                }
                warehouseApproach = InteractionApproach.find(npc, pos);
                if (warehouseApproach == null) {
                    finished = true;
                    return;
                }
                movementProgress.reset(npc, warehouseApproach);
                recalcPath = 0;
            }
            if (--recalcPath <= 0) {
                recalcPath = 20;
                moveToward(warehouseApproach);
            }
            return;
        }

        npc.getNavigation().stop();
        npc.clearLogisticsMoveTarget();
        if (--retryCooldown > 0) return;
        retryCooldown = RETRY_TICKS;
        Container container = containerAt(level, pos);
        if (container == null) return;

        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(order.itemId()));
        if (stage == Stage.TO_SOURCE) {
            int moved = transferOrderItems(level, order, warehouse, container, item, true);
            if (moved <= 0) return;
            order = data.recordPickup(order.id(), npc.getCharacterId(), moved, level.getGameTime());
            npc.playAction(NpcAction.TRADE_PRESENT, 20);
            notifyObservers(level, net.minecraft.network.chat.Component.literal(
                    "§e" + npc.getNpcName() + " 已从 " + warehouse.name() + " 取出 "
                            + moved + " 个 " + order.itemId()));
            stage = Stage.TO_DESTINATION;
            moveTowardCurrentWarehouse();
            reportStage("已取货，前往交付");
        } else {
            int moved = transferOrderItems(level, order, warehouse, container, item, false);
            if (moved <= 0) return;
            order = data.recordDelivery(level.getServer(), order.id(), npc.getCharacterId(), moved, level.getGameTime());
            npc.playAction(NpcAction.TRADE_PRESENT, 20);
            if (order != null) {
                boolean finished = order.status() == LogisticsOrderStatus.DELIVERED;
                notifyObservers(level, net.minecraft.network.chat.Component.literal(
                        (finished ? "§a" : "§e") + npc.getNpcName() + " 已向 " + warehouse.name()
                                + " 交付 " + moved + " 个 " + order.itemId()
                                + (finished ? "，订单完成" : "")));
            }
            if (order != null && order.status() != LogisticsOrderStatus.DELIVERED
                    && order.inTransitCount() == 0 && order.remainingToPickUp() > 0) {
                stage = Stage.TO_SOURCE;
                moveTowardCurrentWarehouse();
                reportStage("继续返回取货");
            }
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.clearLogisticsMoveTarget();
        npc.clearLogisticsStatus();
        orderId = null;
        warehouseApproach = null;
        finished = false;
    }

    private void moveTowardCurrentWarehouse() {
        if (orderId == null || !(npc.level() instanceof ServerLevel level)) return;
        LogisticsData data = LogisticsData.get(level.getServer());
        LogisticsOrderRecord order = data.order(orderId);
        if (order == null) return;
        WarehouseRecord warehouse = data.warehouse(stage == Stage.TO_SOURCE
                ? order.sourceWarehouseId() : order.destinationWarehouseId());
        if (warehouse == null) return;
        warehouseApproach = isInInteractionRange(warehouse.position())
                ? npc.blockPosition() : InteractionApproach.find(npc, warehouse.position());
        if (warehouseApproach == null) {
            finished = true;
            return;
        }
        movementProgress.reset(npc, warehouseApproach);
        recalcPath = 0;
        moveToward(warehouseApproach);
    }

    private void moveToward(BlockPos destination) {
        BlockPos waypoint = RoadRoutePlanner.nextWaypoint(npc, destination);
        npc.setLogisticsMoveTarget(waypoint);
        if (npc.isPassenger()) {
            npc.getNavigation().stop();
        } else {
            npc.getNavigation().moveTo(waypoint.getX() + 0.5D, waypoint.getY(), waypoint.getZ() + 0.5D, 1.05D);
        }
    }

    private boolean isTransportProfession() {
        return npc.getProfession() == NpcProfession.COURIER
                || npc.getProfession() == NpcProfession.TRADER;
    }

    private boolean isInInteractionRange(BlockPos pos) {
        return npc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= INTERACTION_RANGE_SQ;
    }

    private void reportStage(String detail) {
        if (orderId == null || !(npc.level() instanceof ServerLevel level)) return;
        LogisticsOrderRecord order = LogisticsData.get(level.getServer()).order(orderId);
        if (order == null) return;
        WarehouseRecord warehouse = LogisticsData.get(level.getServer()).warehouse(
                stage == Stage.TO_SOURCE ? order.sourceWarehouseId() : order.destinationWarehouseId());
        npc.reportWorkActivity(stage == Stage.TO_SOURCE ? "物流前往取货" : "物流前往交付",
                warehouse == null ? null : warehouse.position(), detail + "，货物=" + order.itemId());
    }

    private void refreshClientStatus() {
        if (orderId == null || !(npc.level() instanceof ServerLevel level)) return;
        LogisticsOrderRecord order = LogisticsData.get(level.getServer()).order(orderId);
        if (order == null) return;
        npc.syncLogisticsStatus(stage == Stage.TO_SOURCE ? 1 : 2, order.itemId(),
                order.deliveredCount(), order.requestedCount(), cargoCount(order.itemId()));
    }

    /** 玩家所属 NPC 只提示主人；机构 NPC 向近距离观察者显示，避免多人服务器全局刷屏。 */
    private void showRouteFeedback(ServerLevel level, BlockPos destination) {
        net.minecraft.world.phys.Vec3 start = npc.position().add(0.0D, 1.0D, 0.0D);
        net.minecraft.world.phys.Vec3 direction = net.minecraft.world.phys.Vec3.atCenterOf(destination).subtract(start);
        double length = direction.length();
        if (length < 0.1D) return;
        net.minecraft.world.phys.Vec3 step = direction.scale(Math.min(8.0D, length) / length / 6.0D);
        org.joml.Vector3f color = stage == Stage.TO_SOURCE
                ? new org.joml.Vector3f(1.0F, 0.78F, 0.18F)
                : new org.joml.Vector3f(0.18F, 0.85F, 1.0F);
        net.minecraft.core.particles.DustParticleOptions particle =
                new net.minecraft.core.particles.DustParticleOptions(com.jgeted.sagadyssey.core.MinecraftApi.particleColor(color), 0.7F);
        if (npc.getActiveOwner() instanceof net.minecraft.server.level.ServerPlayer owner) {
            if (owner.level() == level && owner.distanceToSqr(npc) <= 96.0D * 96.0D) {
                drawRouteLine(level, owner, particle, start, step);
            }
            return;
        }
        if (npc.getHomeSettlementId() == null) return;
        for (net.minecraft.server.level.ServerPlayer observer : level.players()) {
            if (observer.distanceToSqr(npc) <= 64.0D * 64.0D) {
                drawRouteLine(level, observer, particle, start, step);
            }
        }
    }

    private static void drawRouteLine(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                                      net.minecraft.core.particles.DustParticleOptions particle,
                                      net.minecraft.world.phys.Vec3 start,
                                      net.minecraft.world.phys.Vec3 step) {
        for (int i = 1; i <= 6; i++) {
            net.minecraft.world.phys.Vec3 point = start.add(step.scale(i));
            level.sendParticles(player, particle, true, false, point.x, point.y, point.z,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private void notifyObservers(ServerLevel level, net.minecraft.network.chat.Component message) {
        if (npc.getActiveOwner() != null) {
            npc.getActiveOwner().displayClientMessage(message, true);
            return;
        }
        if (npc.getHomeSettlementId() == null) return;
        for (net.minecraft.server.level.ServerPlayer observer : level.players()) {
            if (observer.distanceToSqr(npc) <= 64.0D * 64.0D) {
                observer.displayClientMessage(message, true);
            }
        }
    }

    @Nullable
    private static Container containerAt(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        return WarehouseContainers.asContainer(level.getBlockEntity(pos));
    }

    private int cargoCount(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return 0;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        int count = 0;
        int slots = Math.min(npc.getTransportSlotLimit(), npc.getTransportInventory().getContainerSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = npc.getTransportInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /** Pickup and delivery both use the same rights-checked atomic transfer ledger. */
    private int transferOrderItems(ServerLevel level, LogisticsOrderRecord order,
                                   WarehouseRecord warehouse, Container warehouseContainer,
                                   Item item, boolean pickup) {
        Container cargo = new LimitedContainerView(npc.getTransportInventory(), npc.getTransportSlotLimit());
        Container source = pickup ? warehouseContainer : cargo;
        Container destination = pickup ? cargo : warehouseContainer;
        int requested = pickup ? order.remainingToPickUp() : order.inTransitCount();
        if (requested <= 0) return 0;
        ItemStack sample = firstStack(source, item);
        if (sample.isEmpty()) return 0;
        int quantity = Math.min(requested, Math.min(countMatching(source, sample),
                capacityForStack(destination, sample)));
        if (quantity <= 0) return 0;

        RightsStateData rights = RightsStateData.get(level.getServer());
        UUID cargoId = ContainerIds.npcCargo(npc.getCharacterId());
        SubjectRef courier = SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(cargoId, "npc:" + npc.getCharacterId() + ":cargo",
                courier, courier, AccessPolicy.CONTRACT);

        UUID warehouseId = warehouse.id();
        SubjectRef warehouseInstitution = SubjectRef.institution(warehouse.settlementId());
        rights.ensureContext(warehouseId, warehouse.dimension() + ':' + warehouse.position().asLong(),
                warehouseInstitution, warehouseInstitution, AccessPolicy.INSTITUTION);

        UUID sourceId = pickup ? warehouseId : cargoId;
        UUID destinationId = pickup ? cargoId : warehouseId;
        rights.grant(new RightGrant(ContainerIds.named("logistics_grant:" + order.id() + ':' + sourceId),
                sourceId, courier, Set.of(AssetRight.ACCESS, AssetRight.POSSESS, AssetRight.TRANSFER),
                "logistics_order:" + order.id(), level.getGameTime(),
                level.getGameTime() + 1_200L, true));

        TransferRequest request = new TransferRequest(
                ContainerIds.named("logistics:" + order.id() + ':' + (pickup ? "pickup:" + order.pickedUpCount()
                        : "delivery:" + order.deliveredCount())),
                courier, pickup ? TransferIntent.ISSUE : TransferIntent.DELIVERY,
                List.of(new TransferLeg(sourceId, destinationId, ItemFingerprint.of(sample), quantity)),
                List.of(new ExpectedRevision(sourceId, rights.context(sourceId).revision()),
                        new ExpectedRevision(destinationId, rights.context(destinationId).revision())),
                "logistics_order:" + order.id(), level.getGameTime());
        Map<UUID, TransferEndpoint> endpoints = Map.of(
                sourceId, new TransferEndpoint(sourceId, source),
                destinationId, new TransferEndpoint(destinationId, destination));
        var receipt = new TransferService(rights, TransferLedgerData.get(level.getServer()))
                .submit(request, endpoints, level.getGameTime());
        if (!receipt.successful()) {
            reportStage("转移暂缓：" + receipt.reason());
            return 0;
        }
        return quantity;
    }

    private static ItemStack firstStack(Container container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static int countMatching(Container container, ItemStack sample) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, sample)) count += stack.getCount();
        }
        return count;
    }

    private static int capacityForStack(Container container, ItemStack sample) {
        int capacity = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) capacity += Math.min(container.getMaxStackSize(), sample.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(stack, sample)) {
                capacity += Math.max(0, Math.min(container.getMaxStackSize(), stack.getMaxStackSize())
                        - stack.getCount());
            }
        }
        return capacity;
    }

}
