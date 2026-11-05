package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcNeedsRecord;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * 从随身背包、私人储物或家庭共享储物中消耗一份真实食物。
 * 公共仓库由聚落日结统一扣除，故这里不会再次取用公共口粮。
 */
public final class NpcEatGoal extends Goal {
    private static final int SEEK_HUNGER = 350;
    private static final int EMERGENCY_HUNGER = 800;
    private static final double SEARCH_RANGE_SQ = 64.0D * 64.0D;
    private static final double INTERACTION_RANGE_SQ = 9.0D;

    private enum Source { BAG, PRIVATE, HOUSEHOLD }

    private final NpcBase npc;
    private final MovementProgress movementProgress = new MovementProgress();
    @Nullable private Source source;
    @Nullable private BlockPos storage;
    @Nullable private BlockPos approach;
    private int pathFailures;
    private int recalcPath;
    private boolean finished;

    public NpcEatGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        clearTarget();
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isSleeping()
                || com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                || npc.getTarget() != null || (npc.tickCount - npc.getId()) % 40 != 0) return false;
        NpcLifeData data = NpcLifeData.get(level.getServer());
        NpcLifeRecord life = data.record(npc.getCharacterId());
        if (life == null) return false;
        NpcNeedsRecord needs = data.ensureNeeds(npc.getCharacterId(), level.getGameTime());
        if (needs.hunger() < SEEK_HUNGER) return false;
        boolean emergency = needs.hunger() >= EMERGENCY_HUNGER;

        if (NpcFoodItems.findFoodSlot(npc.getEquipmentInventory(), emergency) >= 0) {
            source = Source.BAG;
        } else if (usableFoodStorage(level, life.privateStoragePosition(), emergency)) {
            source = Source.PRIVATE;
            storage = life.privateStoragePosition();
        } else {
            HouseholdRecord household = HouseholdData.get(level.getServer()).forMember(npc.getCharacterId());
            if (household != null && household.dimension().equals(
                    level.dimension().location().toString())
                    && usableFoodStorage(level, household.sharedStorage(), emergency)) {
                source = Source.HOUSEHOLD;
                storage = household.sharedStorage();
            }
        }
        if (source == null) {
            if (emergency && level.getGameTime() >= life.decisionLockedUntil()) {
                data.updateDecision(npc.getCharacterId(), "报告缺少可取食物",
                        "饥饿=" + needs.hunger() + "/1000；背包、私人储物和家庭共享储物均无可用食物",
                        level.getGameTime(), level.getGameTime() + 600L, life.fatigue());
            }
            return false;
        }
        if (storage != null) {
            approach = isInInteractionRange(storage)
                    ? npc.blockPosition() : InteractionApproach.find(npc, storage);
            if (approach == null) {
                clearTarget();
                return false;
            }
        }
        if (!npc.requestMutex(AiMutex.HUNGRY)) {
            clearTarget();
            return false;
        }
        if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.EAT,
                emergency ? 30.0D : 14.0D, npc.getCommand() == NpcCommand.PATROL ? 6.0D : 1.0D,
                emergency)) {
            npc.releaseMutex(AiMutex.HUNGRY);
            clearTarget();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && source != null && npc.isAlive() && npc.getTarget() == null
                && !com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc);
    }

    @Override
    public void start() {
        finished = false;
        pathFailures = 0;
        recalcPath = 0;
        if (source == Source.BAG) {
            consumeFrom(npc.getEquipmentInventory(), "随身背包");
            return;
        }
        npc.dismountToBind();
        if (approach != null) movementProgress.reset(npc, approach);
        npc.reportWorkActivity("回家取食", storage, source == Source.PRIVATE
                ? "前往私人储物" : "前往家庭共享储物");
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level) || storage == null) {
            finished = true;
            return;
        }
        npc.getLookControl().setLookAt(storage.getX() + 0.5D,
                storage.getY() + 0.5D, storage.getZ() + 0.5D);
        if (!InteractionApproach.readyToInteract(
                npc, storage, approach, INTERACTION_RANGE_SQ)) {
            if (approach == null || movementProgress.tick(npc, approach)) {
                if (++pathFailures >= 3) {
                    npc.reportWorkActivity("回家取食", storage, "连续寻路失败，稍后重试");
                    finished = true;
                    return;
                }
                approach = InteractionApproach.find(npc, storage);
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
        Container container = WarehouseContainers.asContainer(level.getBlockEntity(storage));
        consumeFrom(container, source == Source.PRIVATE ? "私人储物" : "家庭共享储物");
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.releaseMutex(AiMutex.HUNGRY);
        clearTarget();
        finished = false;
    }

    private void consumeFrom(@Nullable Container container, String sourceName) {
        if (!(npc.level() instanceof ServerLevel level) || container == null) {
            finished = true;
            return;
        }
        NpcNeedsRecord needs = NpcLifeData.get(level.getServer()).ensureNeeds(
                npc.getCharacterId(), level.getGameTime());
        int slot = NpcFoodItems.findFoodSlot(container, needs.hunger() >= EMERGENCY_HUNGER);
        if (slot < 0) {
            npc.reportWorkActivity("回家取食", storage, sourceName + "已没有可吃的食物");
            finished = true;
            return;
        }
        ItemStack food = container.getItem(slot);
        int restored = NpcFoodItems.hungerRestore(food);
        String foodName = food.getHoverName().getString();
        RightsStateData rights = RightsStateData.get(level.getServer());
        SubjectRef character = SubjectRef.character(npc.getCharacterId());
        UUID containerId;
        SubjectRef consumingAs;
        AccessPolicy policy;
        if (source == Source.BAG) {
            containerId = ContainerIds.npcPersonal(npc.getCharacterId());
            consumingAs = character;
            policy = AccessPolicy.PRIVATE;
        } else if (source == Source.PRIVATE) {
            containerId = ContainerIds.block("private_storage",
                    level.dimension().location().toString(), storage);
            consumingAs = character;
            policy = AccessPolicy.PRIVATE;
        } else {
            HouseholdRecord household = HouseholdData.get(level.getServer())
                    .forMember(npc.getCharacterId());
            if (household == null) {
                finished = true;
                return;
            }
            containerId = ContainerIds.block("household_storage",
                    level.dimension().location().toString(), storage);
            consumingAs = SubjectRef.household(household.id());
            policy = AccessPolicy.HOUSEHOLD;
        }
        rights.ensureContext(containerId, "food:" + level.dimension().location() + ':'
                        + (storage == null ? npc.getCharacterId() : storage.asLong()),
                consumingAs, consumingAs, policy);
        ConsumptionService.Result result = new ConsumptionService(rights).consume(containerId,
                container, consumingAs, ItemFingerprint.of(food), 1,
                "food:" + npc.getCharacterId() + ':' + level.getGameTime(), level.getGameTime());
        if (!result.successful()) {
            npc.reportWorkActivity("进食失败", storage, result.reason());
            finished = true;
            return;
        }
        NpcLifeData.get(level.getServer()).feed(npc.getCharacterId(), restored,
                sourceName + "的" + foodName, level.getGameTime());
        npc.playAction(NpcAction.EAT, 30);
        npc.playSound(SoundEvents.GENERIC_EAT, 0.75F, 0.9F + npc.getRandom().nextFloat() * 0.2F);
        npc.reportWorkActivity("进食", storage,
                "吃下 " + foodName + "，饥饿降低 " + restored + "；来源=" + sourceName);
        finished = true;
    }

    private boolean usableFoodStorage(ServerLevel level, @Nullable BlockPos position,
                                      boolean emergency) {
        if (position == null || position.distSqr(npc.blockPosition()) > SEARCH_RANGE_SQ
                || !level.hasChunkAt(position)) return false;
        Container container = WarehouseContainers.asContainer(level.getBlockEntity(position));
        return container != null && NpcFoodItems.findFoodSlot(container, emergency) >= 0;
    }

    private boolean isInInteractionRange(BlockPos position) {
        return npc.distanceToSqr(position.getX() + 0.5D,
                position.getY() + 0.5D, position.getZ() + 0.5D) <= INTERACTION_RANGE_SQ;
    }

    private void clearTarget() {
        source = null;
        storage = null;
        approach = null;
    }
}
