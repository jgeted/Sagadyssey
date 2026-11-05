package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.production.ProductionBatch;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
import com.jgeted.sagadyssey.social.production.ProductionService;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** 铁匠在工作站消耗铁锭维修盟友装备，空闲时可把煤和铁锭锻造成基础铁制工具。 */
public class BlacksmithWorkGoal extends Goal {
    private static final EquipmentSlot[] REPAIR_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final NpcBase npc;
    private BlockPos station;
    private BlockPos approach;
    private ItemStack repairStack = ItemStack.EMPTY;
    private int workTicks;
    private int recalcPath;
    private int pathFailures;
    private long nextForgeTick;
    private boolean forge;
    private boolean finished;
    private UUID productionBatchId;
    private final MovementProgress movementProgress = new MovementProgress();

    public BlacksmithWorkGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.level().isClientSide
                || npc.getProfession() != NpcProfession.BLACKSMITH
                || npc.getCommand() != NpcCommand.WORK
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)
                || npc.getTarget() != null || npc.isPassenger()) return false;
        if ((npc.tickCount - npc.getId()) % 20 != 0) return false;
        station = findStation();
        if (station == null) return false;

        repairStack = findRepairStack();
        forge = repairStack.isEmpty() && npc.level().getGameTime() >= nextForgeTick
                && npc.hasBagItem(Items.IRON_INGOT, 2) && npc.hasBagItem(Items.COAL, 1)
                && npc.hasBagSpaceFor(new ItemStack(Items.IRON_SWORD));
        if (repairStack.isEmpty() && !forge) return false;
        if (!forge && !npc.hasBagItem(Items.IRON_INGOT, 1)) return false;
        approach = isInInteractionRange() ? npc.blockPosition() : InteractionApproach.find(npc, station);
        if (approach == null) return false;
        return NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.WORK,
                forge ? 42.0D : 48.0D, forge ? 4.0D : 2.0D, false);
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return !finished && npc.getCommand() == NpcCommand.WORK && npc.getTarget() == null
                && station != null && (forge || !repairStack.isEmpty())
                && com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc);
    }

    @Override
    public void start() {
        workTicks = 0;
        recalcPath = 0;
        pathFailures = 0;
        finished = false;
        movementProgress.reset(npc, approach);
        InteractionApproach.move(npc, approach, 1.0D);
        npc.reportWorkActivity(forge ? "铁匠锻造工具" : "铁匠维修装备", station,
                "交互站位=" + approach.toShortString());
        if (forge && !reserveForgeBatch()) finished = true;
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        npc.getLookControl().setLookAt(station.getX() + 0.5D, station.getY() + 0.5D,
                station.getZ() + 0.5D, 25.0F, npc.getMaxHeadXRot());
        if (!InteractionApproach.readyToInteract(npc, station, approach, 6.25D)) {
            if (approach == null) {
                finished = true;
                return;
            }
            if (movementProgress.tick(npc, approach)) {
                if (++pathFailures >= 3) {
                    npc.reportWorkActivity(forge ? "铁匠锻造工具" : "铁匠维修装备",
                            station, "寻路连续失败，稍后重试");
                    finished = true;
                    return;
                }
                approach = InteractionApproach.find(npc, station);
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
        workTicks++;
        if (workTicks == 1) npc.playAction(NpcAction.SMITH, 38);
        if (workTicks < 34) return;

        if (forge) {
            if (productionBatchId != null && npc.level() instanceof ServerLevel level) {
                try {
                    ProductionService.complete(level.getServer(), productionBatchId,
                            npc.getEquipmentInventory(), workTicks, level.getGameTime());
                    productionBatchId = null;
                    npc.addExperience(3);
                    nextForgeTick = npc.level().getGameTime() + 1200L;
                } catch (RuntimeException exception) {
                    npc.reportWorkActivity("铁匠锻造工具", station,
                            "生产批次失败：" + exception.getMessage());
                }
            }
        } else if (!repairStack.isEmpty() && repairStack.isDamageableItem()
                && consumeRepairMaterial()) {
            int repair = Math.max(1, repairStack.getMaxDamage() / 4);
            repairStack.setDamageValue(Math.max(0, repairStack.getDamageValue() - repair));
            npc.addExperience(2);
        }
        npc.playSound(SoundEvents.ANVIL_USE, 0.8F, 1.0F);
        npc.reportWorkActivity(forge ? "铁匠锻造工具" : "铁匠维修装备", station, "本轮操作完成");
        finished = true;
    }

    @Override
    public void stop() {
        if (productionBatchId != null && npc.level() instanceof ServerLevel level) {
            ProductionService.cancel(level.getServer(), productionBatchId, level.getGameTime(),
                    "工作中断，原料预留释放");
            productionBatchId = null;
        }
        npc.getNavigation().stop();
        station = null;
        approach = null;
        repairStack = ItemStack.EMPTY;
        forge = false;
        finished = false;
    }

    private boolean reserveForgeBatch() {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        RightsStateData rights = RightsStateData.get(level.getServer());
        UUID bagId = ContainerIds.npcPersonal(npc.getCharacterId());
        SubjectRef worker = SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(bagId, "npc:" + npc.getCharacterId() + ":personal",
                worker, worker, AccessPolicy.PRIVATE);
        SubjectRef ironOwner = ownerOfFirstBatch(rights, bagId, Items.IRON_INGOT, worker);
        SubjectRef coalOwner = ownerOfFirstBatch(rights, bagId, Items.COAL, worker);
        if (!ironOwner.equals(coalOwner)) {
            npc.reportWorkActivity("铁匠锻造工具", station,
                    "铁锭与燃料分属不同主体，不能擅自混合生产");
            return false;
        }
        ItemStack result = new ItemStack(npc.getRandom().nextBoolean()
                ? Items.IRON_SWORD : Items.IRON_PICKAXE);
        try {
            ProductionBatch batch = ProductionService.begin(level.getServer(), npc.getCharacterId(),
                    ironOwner, bagId, npc.getEquipmentInventory(), "iron_tool",
                    List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)), 2),
                            new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)), 1)),
                    new ProductionMaterial(ItemFingerprint.of(result), 1), 34,
                    level.getGameTime());
            productionBatchId = batch.id();
            return true;
        } catch (RuntimeException exception) {
            npc.reportWorkActivity("铁匠锻造工具", station,
                    "无法预留生产原料：" + exception.getMessage());
            return false;
        }
    }

    private SubjectRef ownerOfFirstBatch(RightsStateData rights, UUID bagId,
                                         net.minecraft.world.item.Item item,
                                         SubjectRef fallback) {
        for (int slot = 0; slot < npc.getEquipmentInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getEquipmentInventory().getItem(slot);
            if (!stack.is(item)) continue;
            var claim = rights.claimAt(bagId, slot, ItemFingerprint.of(stack));
            return claim == null ? fallback : claim.beneficialOwner();
        }
        return fallback;
    }

    private boolean consumeRepairMaterial() {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        RightsStateData rights = RightsStateData.get(level.getServer());
        UUID bagId = ContainerIds.npcPersonal(npc.getCharacterId());
        SubjectRef worker = SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(bagId, "npc:" + npc.getCharacterId() + ":personal",
                worker, worker, AccessPolicy.PRIVATE);
        ConsumptionService.Result result = new ConsumptionService(rights).consume(bagId,
                npc.getEquipmentInventory(), worker,
                ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)), 1,
                "repair:" + npc.getCharacterId() + ':' + level.getGameTime(),
                level.getGameTime());
        if (!result.successful()) {
            npc.reportWorkActivity("铁匠维修装备", station,
                    "维修材料不可消费：" + result.reason());
        }
        return result.successful();
    }

    private BlockPos findStation() {
        BlockPos origin = npc.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -3, -12), origin.offset(12, 3, 12))) {
            var state = npc.level().getBlockState(pos);
            if (!(state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL)
                    || state.is(Blocks.DAMAGED_ANVIL) || state.is(Blocks.SMITHING_TABLE))) continue;
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private ItemStack findRepairStack() {
        // Ally status is not permission to alter another person's property. A later repair-order
        // domain can supply explicit consent and payment; the autonomous job repairs only self-owned gear.
        return mostDamagedStack(npc);
    }

    private ItemStack mostDamagedStack(NpcBase target) {
        ItemStack best = ItemStack.EMPTY;
        double bestRatio = 0.0D;
        for (EquipmentSlot slot : REPAIR_SLOTS) {
            ItemStack stack = target.getItemBySlot(slot);
            if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
                double ratio = (double) stack.getDamageValue() / stack.getMaxDamage();
                if (ratio > bestRatio) { best = stack; bestRatio = ratio; }
            }
        }
        for (int i = 0; i < target.getEquipmentInventory().getContainerSize(); i++) {
            ItemStack stack = target.getEquipmentInventory().getItem(i);
            if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
                double ratio = (double) stack.getDamageValue() / stack.getMaxDamage();
                if (ratio > bestRatio) { best = stack; bestRatio = ratio; }
            }
        }
        return best;
    }

    private boolean isInInteractionRange() {
        return station != null && npc.distanceToSqr(
                station.getX() + 0.5D, station.getY() + 0.5D, station.getZ() + 0.5D) <= 6.25D;
    }
}
