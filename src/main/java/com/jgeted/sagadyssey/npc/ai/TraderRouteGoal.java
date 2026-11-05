package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.social.market.LocalMarketData;
import com.jgeted.sagadyssey.social.market.MerchantMarketKnowledgeData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** 商人在 WORK 状态沿工作区/市场节点巡回，到站展示货物并触发地区补货检查。 */
public class TraderRouteGoal extends Goal {
    private final NpcBase npc;
    private BlockPos destination;
    private BlockPos marketTarget;
    private int routeIndex;
    private int travelTicks;
    private boolean arrived;

    public TraderRouteGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.level().isClientSide
                || npc.getProfession() != NpcProfession.TRADER
                || npc.getCommand() != NpcCommand.WORK
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)
                || npc.getTarget() != null) return false;
        if ((npc.tickCount - npc.getId()) % 40 != 0) return false;
        // 有真实运输任务时让物流 Goal 优先；市场巡回只承担无订单时的可见行为。
        if (hasLogisticsWork()) return false;
        destination = chooseDestination();
        if (destination != null && !isStandable(destination)) {
            marketTarget = destination;
            destination = InteractionApproach.find(npc, marketTarget);
        } else {
            marketTarget = destination;
        }
        return destination != null
                && NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.WORK,
                46.0D, 4.0D, false);
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return !arrived && travelTicks < 300 && destination != null
                && npc.getCommand() == NpcCommand.WORK && npc.getTarget() == null
                && com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc);
    }

    @Override
    public void start() {
        travelTicks = 0;
        arrived = false;
        moveToDestination();
        npc.reportWorkActivity("商人市场巡回", marketTarget, "当前没有物流订单");
    }

    @Override
    public void tick() {
        travelTicks++;
        BlockPos look = marketTarget == null ? destination : marketTarget;
        npc.getLookControl().setLookAt(look.getX() + 0.5D, look.getY() + 0.5D,
                look.getZ() + 0.5D, 20.0F, npc.getMaxHeadXRot());
        Entity mover = npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
        if (mover.distanceToSqr(Vec3.atBottomCenterOf(destination)) > 4.0D) {
            if (travelTicks % 30 == 0) {
                moveToDestination();
            }
            return;
        }
        npc.getNavigation().stop();
        npc.clearLogisticsMoveTarget();
        npc.playAction(NpcAction.TRADE_PRESENT, 45);
        npc.playSound(SoundEvents.VILLAGER_TRADE, 0.7F, 1.0F);
        npc.restockMerchantIfNeeded();
        observeLocalMarket();
        npc.reportWorkActivity("商人市场巡回", marketTarget, "已到达市场节点并展示货物");
        routeIndex++;
        arrived = true;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.clearLogisticsMoveTarget();
        destination = null;
        marketTarget = null;
        arrived = false;
    }

    private BlockPos chooseDestination() {
        if (npc.hasWorkZone()) {
            BlockPos min = npc.getWorkZoneMin();
            BlockPos max = npc.getWorkZoneMax();
            BlockPos[] route = {
                    new BlockPos(min.getX(), min.getY(), min.getZ()),
                    new BlockPos(max.getX(), min.getY(), min.getZ()),
                    new BlockPos(max.getX(), min.getY(), max.getZ()),
                    new BlockPos(min.getX(), min.getY(), max.getZ())
            };
            return route[Math.floorMod(routeIndex, route.length)];
        }

        List<BlockPos> markets = findMarketNodes();
        if (!markets.isEmpty()) {
            markets.sort(Comparator.comparingDouble(pos -> -pos.distSqr(npc.blockPosition())));
            return markets.get(Math.floorMod(routeIndex, markets.size()));
        }
        Vec3 random = LandRandomPos.getPos(npc, 14, 5);
        return random == null ? null : BlockPos.containing(random);
    }

    private List<BlockPos> findMarketNodes() {
        BlockPos origin = npc.blockPosition();
        List<BlockPos> result = new ArrayList<>();
        if (npc.level() instanceof ServerLevel level) {
            NpcLifeRecord life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
            if (life != null && life.activeWorkplace() != null
                    && life.dimension().equals(level.dimension().location().toString())) {
                origin = life.activeWorkplace();
            }
            BlockPos marketOrigin = origin;
            com.jgeted.sagadyssey.social.logistics.LogisticsData.get(level.getServer()).warehouses().stream()
                    .filter(record -> record.dimension().equals(level.dimension().location().toString()))
                    .filter(record -> record.position().distSqr(marketOrigin) <= 96.0D * 96.0D)
                    .filter(record -> level.hasChunkAt(record.position()))
                    .map(com.jgeted.sagadyssey.social.logistics.WarehouseRecord::position)
                    .forEach(result::add);
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-14, -2, -14), origin.offset(14, 2, 14))) {
            var state = npc.level().getBlockState(pos);
            if (state.is(Blocks.BELL) || state.is(Blocks.BARREL) || state.is(Blocks.CHEST)
                    || state.is(Blocks.CARTOGRAPHY_TABLE)) {
                if (!result.contains(pos)) result.add(pos.immutable());
            }
        }
        return result;
    }

    private boolean hasLogisticsWork() {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        LogisticsData logistics = LogisticsData.get(level.getServer());
        NpcWorkContext context = NpcWorkContext.from(npc);
        String dimension = level.dimension().location().toString();
        return logistics.orders().stream().anyMatch(order -> {
            if (order.status() == LogisticsOrderStatus.IN_PROGRESS) {
                return npc.getCharacterId().equals(order.assignedNpcId());
            }
            if (order.status() != LogisticsOrderStatus.PLANNED
                    || !context.canClaimOrder(logistics, order)) return false;
            var source = logistics.warehouse(order.sourceWarehouseId());
            var destination = logistics.warehouse(order.destinationWarehouseId());
            return source != null && destination != null
                    && dimension.equals(source.dimension())
                    && dimension.equals(destination.dimension());
        });
    }

    private void observeLocalMarket() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        var settlement = SettlementRegistryData.get(level.getServer()).nearest(
                level.dimension().location().toString(), npc.blockPosition(), 256.0D);
        if (settlement == null) return;
        int learned = MerchantMarketKnowledgeData.get(level.getServer()).observe(
                npc.getCharacterId(), settlement.id(), npc.getEquipmentInventory(),
                LocalMarketData.get(level.getServer()), level.getGameTime());
        if (learned > 0) {
            npc.reportWorkActivity("商人观察市场", marketTarget,
                    "记录了 " + learned + " 类货物的本地真实成交信息");
        }
    }

    private void moveToDestination() {
        if (destination == null) return;
        if (npc.isPassenger()) {
            npc.setLogisticsMoveTarget(RoadRoutePlanner.nextWaypoint(npc, destination));
            npc.getNavigation().stop();
        } else {
            npc.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(),
                    destination.getZ() + 0.5D, 0.9D);
        }
    }

    private boolean isStandable(BlockPos pos) {
        return npc.level().getBlockState(pos).getCollisionShape(npc.level(), pos).isEmpty()
                && npc.level().getBlockState(pos.above()).getCollisionShape(npc.level(), pos.above()).isEmpty();
    }
}
