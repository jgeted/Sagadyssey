package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.work.NpcWorkstationContainers;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 固定聚落铁匠会从实体仓库领取原料，在真实熔炉中冶炼，并取回铁锭。 */
public final class BlacksmithSmeltGoal extends Goal {
    private static final double INTERACTION_RANGE_SQ = 9.0D;
    private static final int RAW_IRON_BATCH = 4;

    private final NpcBase npc;
    @Nullable private BlockPos target;
    @Nullable private BlockPos approach;
    @Nullable private WarehouseRecord warehouse;
    private Mode mode;
    private int recalcPath;
    private int pathFailures;
    private int nextWorkScanTick;
    private boolean finished;
    private final MovementProgress movementProgress = new MovementProgress();

    private enum Mode { FETCH_SUPPLIES, LOAD_FURNACE, COLLECT_OUTPUT }

    public BlacksmithSmeltGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isPassenger()
                || npc.getProfession() != NpcProfession.BLACKSMITH
                || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        if (npc.tickCount < nextWorkScanTick) return false;
        nextWorkScanTick = npc.tickCount + 20;

        BlockPos furnace = findFurnace(level);
        if (furnace == null) return idle("附近 16 格没有熔炉或高炉");
        Container furnaceInventory = NpcWorkstationContainers.asFurnace(level.getBlockEntity(furnace));
        if (furnaceInventory == null || furnaceInventory.getContainerSize() < 3) {
            return idle("找到熔炉，但方块实体尚未就绪");
        }

        ItemStack output = furnaceInventory.getItem(2);
        if (output.is(Items.IRON_INGOT) && npc.hasBagSpaceFor(output.copy())) {
            mode = Mode.COLLECT_OUTPUT;
            target = furnace;
        } else if (commonInputOwner(level) != null && canLoad(furnaceInventory)) {
            mode = Mode.LOAD_FURNACE;
            target = furnace;
        } else {
            warehouse = findWarehouse(level);
            if (warehouse == null) return idle("随身缺少原铁或煤，且附近没有获准使用的仓库");
            Container stock = WarehouseContainers.asContainer(level.getBlockEntity(warehouse.position()));
            RightsStateData rights = RightsStateData.get(level.getServer());
            UUID bagId = ensureBagContext(rights);
            SubjectRef institution = SubjectRef.institution(warehouse.settlementId());
            boolean needsIron = countOwned(rights, bagId, Items.RAW_IRON, institution) <= 0;
            boolean needsCoal = countOwned(rights, bagId, Items.COAL, institution) <= 0;
            if (stock == null) return idle("获准仓库的容器未加载");
            if (needsIron && count(stock, Items.RAW_IRON) <= 0) return idle("获准仓库没有原铁");
            if (needsCoal && count(stock, Items.COAL) <= 0) return idle("获准仓库没有煤");
            int ironToFetch = needsIron ? Math.min(RAW_IRON_BATCH, count(stock, Items.RAW_IRON)) : 0;
            ItemStack ironStack = new ItemStack(Items.RAW_IRON, ironToFetch);
            ItemStack coalStack = needsCoal ? new ItemStack(Items.COAL) : ItemStack.EMPTY;
            if (!npc.hasBagSpaceForAll(ironStack, coalStack)) {
                return idle("工作背包没有空间领取冶炼原料");
            }
            mode = Mode.FETCH_SUPPLIES;
            target = warehouse.position();
        }
        approach = isInInteractionRange(target) ? npc.blockPosition() : InteractionApproach.find(npc, target);
        if (approach == null) {
            target = null;
            warehouse = null;
            mode = null;
            return false;
        }
        return NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.WORK,
                54.0D, mode == Mode.FETCH_SUPPLIES ? 3.0D : 1.0D, false);
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return !finished && target != null && npc.isAlive() && npc.getTarget() == null
                && npc.getCommand() == NpcCommand.WORK
                && com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc);
    }

    @Override
    public void start() {
        finished = false;
        recalcPath = 0;
        pathFailures = 0;
        if (approach != null) movementProgress.reset(npc, approach);
        npc.reportWorkActivity(activityName(), target,
                "交互站位=" + (approach == null ? "无" : approach.toShortString()));
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        if (target == null || !(npc.level() instanceof ServerLevel level)) return;
        npc.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        if (!InteractionApproach.readyToInteract(
                npc, target, approach, INTERACTION_RANGE_SQ)) {
            if (approach == null) {
                finished = true;
                return;
            }
            if (movementProgress.tick(npc, approach)) {
                if (++pathFailures >= 3) {
                    npc.reportWorkActivity(activityName(), target, "寻路连续失败，稍后重试");
                    finished = true;
                    return;
                }
                approach = InteractionApproach.find(npc, target);
                if (approach == null) {
                    finished = true;
                    return;
                }
                movementProgress.reset(npc, approach);
                recalcPath = 0;
            }
            if (--recalcPath <= 0) {
                recalcPath = 20;
                if (!InteractionApproach.move(npc, approach, 1.0D)) pathFailures++;
            }
            return;
        }

        npc.getNavigation().stop();
        Container container = mode == Mode.FETCH_SUPPLIES
                ? WarehouseContainers.asContainer(level.getBlockEntity(target))
                : NpcWorkstationContainers.asFurnace(level.getBlockEntity(target));
        if (container == null) {
            finished = true;
            return;
        }
        boolean successful = switch (mode) {
            case FETCH_SUPPLIES -> fetchSupplies(container);
            case LOAD_FURNACE -> loadFurnace(container);
            case COLLECT_OUTPUT -> collectOutput(container);
        };
        if (successful) npc.reportWorkActivity(activityName(), target, "本轮操作完成");
        finished = true;
    }

    private boolean fetchSupplies(Container stock) {
        if (!(npc.level() instanceof ServerLevel level) || warehouse == null) return false;
        RightsStateData rights = RightsStateData.get(level.getServer());
        UUID bagId = ensureBagContext(rights);
        SubjectRef institution = SubjectRef.institution(warehouse.settlementId());
        int rawIron = countOwned(rights, bagId, Items.RAW_IRON, institution) > 0
                ? 0 : Math.min(RAW_IRON_BATCH, count(stock, Items.RAW_IRON));
        int coal = countOwned(rights, bagId, Items.COAL, institution) > 0 ? 0 : 1;
        if ((rawIron <= 0 && coal <= 0) || (coal > 0 && count(stock, Items.COAL) <= 0)) {
            npc.reportWorkActivity(activityName(), target, "仓库中没有可领取的同权属原料");
            return false;
        }
        if (!npc.hasBagSpaceForAll(new ItemStack(Items.RAW_IRON, rawIron),
                coal > 0 ? new ItemStack(Items.COAL) : ItemStack.EMPTY)) {
            npc.reportWorkActivity(activityName(), target, "工作背包没有空间");
            return false;
        }
        UUID warehouseId = warehouse.id();
        rights.ensureContext(warehouseId, warehouse.dimension() + ':' + warehouse.position().asLong(),
                institution, institution, AccessPolicy.INSTITUTION);
        List<TransferLeg> legs = new ArrayList<>();
        if (rawIron > 0) legs.add(new TransferLeg(warehouseId, bagId,
                ItemFingerprint.of(new ItemStack(Items.RAW_IRON)), rawIron));
        if (coal > 0) legs.add(new TransferLeg(warehouseId, bagId,
                ItemFingerprint.of(new ItemStack(Items.COAL)), coal));
        TransferRequest request = new TransferRequest(UUID.randomUUID(), institution,
                TransferIntent.ISSUE, legs,
                List.of(new ExpectedRevision(warehouseId, rights.context(warehouseId).revision()),
                        new ExpectedRevision(bagId, rights.context(bagId).revision())),
                "work_input:smelting:" + warehouse.settlementId(), level.getGameTime(),
                Map.of(warehouseId, institution));
        var receipt = new TransferService(rights, TransferLedgerData.get(level.getServer()))
                .submit(request, Map.of(warehouseId, new TransferEndpoint(warehouseId, stock),
                        bagId, new TransferEndpoint(bagId, npc.getEquipmentInventory())),
                        level.getGameTime());
        if (!receipt.successful()) {
            npc.reportWorkActivity(activityName(), target, "领取失败：" + receipt.reason());
            return false;
        }
        npc.playAction(NpcAction.TRADE_PRESENT, 20);
        return true;
    }

    private boolean loadFurnace(Container furnace) {
        if (!canLoad(furnace) || !(npc.level() instanceof ServerLevel level) || target == null) {
            return false;
        }
        RightsStateData rights = RightsStateData.get(level.getServer());
        UUID bagId = ensureBagContext(rights);
        SubjectRef inputOwner = commonInputOwner(rights, bagId);
        if (inputOwner == null) {
            npc.reportWorkActivity(activityName(), target,
                    "没有同一主体所有的原铁与燃料，等待领取配套原料");
            return false;
        }
        int inputSpace = furnace.getItem(0).isEmpty() ? 64 : 64 - furnace.getItem(0).getCount();
        int amount = Math.min(Math.min(RAW_IRON_BATCH, inputSpace),
                countOwned(rights, bagId, Items.RAW_IRON, inputOwner));
        if (amount <= 0) return false;
        String dimension = level.dimension().location().toString();
        UUID furnaceId = ContainerIds.block("work_furnace", dimension, target);
        var furnaceContext = rights.ensureContext(furnaceId,
                dimension + ":furnace:" + target.asLong(), inputOwner, inputOwner, AccessPolicy.CONTRACT);
        if (!furnaceContext.defaultBeneficialOwner().equals(inputOwner)) {
            npc.reportWorkActivity(activityName(), target, "熔炉中已有其他主体的生产批次");
            return false;
        }
        TransferRequest request = new TransferRequest(UUID.randomUUID(), inputOwner,
                TransferIntent.DELIVERY,
                List.of(new TransferLeg(bagId, furnaceId,
                                ItemFingerprint.of(new ItemStack(Items.RAW_IRON)), amount),
                        new TransferLeg(bagId, furnaceId,
                                ItemFingerprint.of(new ItemStack(Items.COAL)), 1)),
                List.of(new ExpectedRevision(bagId, rights.context(bagId).revision()),
                        new ExpectedRevision(furnaceId, rights.context(furnaceId).revision())),
                "production_batch:iron_smelting", level.getGameTime(),
                Map.of(bagId, inputOwner));
        Container furnaceInputs = new LimitedContainerView(furnace, 2);
        var receipt = new TransferService(rights, TransferLedgerData.get(level.getServer()))
                .submit(request, Map.of(bagId, new TransferEndpoint(bagId, npc.getEquipmentInventory()),
                        furnaceId, new TransferEndpoint(furnaceId, furnaceInputs)), level.getGameTime());
        if (!receipt.successful()) {
            npc.reportWorkActivity(activityName(), target, "装填失败：" + receipt.reason());
            return false;
        }
        npc.playAction(NpcAction.SMITH, 38);
        npc.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, 0.8F, 1.0F);
        return true;
    }

    @Nullable
    private SubjectRef commonInputOwner(ServerLevel level) {
        RightsStateData rights = RightsStateData.get(level.getServer());
        return commonInputOwner(rights, ensureBagContext(rights));
    }

    @Nullable
    private SubjectRef commonInputOwner(RightsStateData rights, UUID bagId) {
        Set<SubjectRef> ironOwners = ownersOf(rights, bagId, Items.RAW_IRON);
        for (SubjectRef owner : ownersOf(rights, bagId, Items.COAL)) {
            if (ironOwners.contains(owner)) return owner;
        }
        return null;
    }

    private Set<SubjectRef> ownersOf(RightsStateData rights, UUID bagId, Item item) {
        Set<SubjectRef> result = new LinkedHashSet<>();
        Container bag = npc.getEquipmentInventory();
        SubjectRef fallback = SubjectRef.character(npc.getCharacterId());
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack stack = bag.getItem(slot);
            if (!stack.is(item)) continue;
            var claim = rights.claimAt(bagId, slot, ItemFingerprint.of(stack));
            result.add(claim == null ? fallback : claim.beneficialOwner());
        }
        return result;
    }

    private int countOwned(RightsStateData rights, UUID bagId, Item item, SubjectRef owner) {
        int total = 0;
        Container bag = npc.getEquipmentInventory();
        SubjectRef fallback = SubjectRef.character(npc.getCharacterId());
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack stack = bag.getItem(slot);
            if (!stack.is(item)) continue;
            var claim = rights.claimAt(bagId, slot, ItemFingerprint.of(stack));
            SubjectRef actualOwner = claim == null ? fallback : claim.beneficialOwner();
            if (actualOwner.equals(owner)) total += stack.getCount();
        }
        return total;
    }

    private UUID ensureBagContext(RightsStateData rights) {
        UUID bagId = ContainerIds.npcPersonal(npc.getCharacterId());
        SubjectRef worker = SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(bagId, "npc:" + npc.getCharacterId() + ":personal",
                worker, worker, AccessPolicy.PRIVATE);
        return bagId;
    }

    private boolean collectOutput(Container furnace) {
        ItemStack output = furnace.getItem(2);
        if (!output.is(Items.IRON_INGOT) || !(npc.level() instanceof ServerLevel level)
                || target == null) return false;
        RightsStateData rights = RightsStateData.get(level.getServer());
        SubjectRef worker = SubjectRef.character(npc.getCharacterId());
        UUID bagId = ContainerIds.npcPersonal(npc.getCharacterId());
        String dimension = level.dimension().location().toString();
        UUID furnaceId = ContainerIds.block("work_furnace", dimension, target);
        rights.ensureContext(bagId, "npc:" + npc.getCharacterId() + ":personal",
                worker, worker, AccessPolicy.PRIVATE);
        var furnaceContext = rights.ensureContext(furnaceId,
                dimension + ":furnace:" + target.asLong(), worker, worker, AccessPolicy.CONTRACT);
        SubjectRef outputOwner = furnaceContext.defaultBeneficialOwner();
        TransferRequest request = new TransferRequest(UUID.randomUUID(), outputOwner,
                TransferIntent.ISSUE,
                List.of(new TransferLeg(furnaceId, bagId, ItemFingerprint.of(output), output.getCount())),
                List.of(new ExpectedRevision(furnaceId, furnaceContext.revision()),
                        new ExpectedRevision(bagId, rights.context(bagId).revision())),
                "production_output:iron_smelting", level.getGameTime(),
                Map.of(furnaceId, outputOwner));
        var receipt = new TransferService(rights, TransferLedgerData.get(level.getServer()))
                .submit(request, Map.of(furnaceId, new TransferEndpoint(furnaceId, furnace),
                        bagId, new TransferEndpoint(bagId, npc.getEquipmentInventory())),
                        level.getGameTime());
        if (!receipt.successful()) {
            npc.reportWorkActivity(activityName(), target, "收取成品失败：" + receipt.reason());
            return false;
        }
        npc.addExperience(2);
        npc.playAction(NpcAction.SMITH, 30);
        return true;
    }

    private boolean canLoad(Container furnace) {
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        return (input.isEmpty() || input.is(Items.RAW_IRON)) && input.getCount() < input.getMaxStackSize()
                && (fuel.isEmpty() || fuel.is(Items.COAL)) && fuel.getCount() < fuel.getMaxStackSize();
    }

    private boolean isInInteractionRange(BlockPos pos) {
        return npc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= INTERACTION_RANGE_SQ;
    }

    private String activityName() {
        if (mode == null) return "铁匠等待";
        return switch (mode) {
            case FETCH_SUPPLIES -> "铁匠领取原料";
            case LOAD_FURNACE -> "铁匠装填熔炉";
            case COLLECT_OUTPUT -> "铁匠收取铁锭";
        };
    }

    @Nullable
    private BlockPos findFurnace(ServerLevel level) {
        BlockPos origin = workAnchor(level);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -3, -16), origin.offset(16, 3, 16))) {
            if (!level.getBlockState(pos).is(Blocks.FURNACE)
                    && !level.getBlockState(pos).is(Blocks.BLAST_FURNACE)) continue;
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    @Nullable
    private WarehouseRecord findWarehouse(ServerLevel level) {
        NpcWorkContext context = NpcWorkContext.from(npc);
        BlockPos origin = workAnchor(level);
        return LogisticsData.get(level.getServer()).warehouses().stream()
                .filter(record -> record.dimension().equals(level.dimension().location().toString()))
                .filter(record -> context.canUseWarehouse(level.getServer(), record))
                .filter(record -> record.position().distSqr(origin) <= 32 * 32)
                .filter(record -> level.hasChunkAt(record.position()))
                .min(Comparator.comparingDouble(record -> record.position().distSqr(npc.blockPosition())))
                .orElse(null);
    }

    private BlockPos workAnchor(ServerLevel level) {
        NpcLifeRecord life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
        if (life != null && life.activeWorkplace() != null
                && life.dimension().equals(level.dimension().location().toString())
                && level.hasChunkAt(life.activeWorkplace())) return life.activeWorkplace();
        return npc.blockPosition();
    }

    private boolean idle(String reason) {
        npc.reportWorkActivity("铁匠等待", null,
                NpcWorkContext.from(npc).describe() + "；" + reason);
        return false;
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) count += container.getItem(slot).getCount();
        }
        return count;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        target = null;
        approach = null;
        warehouse = null;
        mode = null;
        finished = false;
    }
}
