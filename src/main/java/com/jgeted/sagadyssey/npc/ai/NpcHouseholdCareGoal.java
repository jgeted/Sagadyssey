package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdCareRecord;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcMemoryRecord;
import com.jgeted.sagadyssey.social.life.NpcNeedsRecord;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 温饱稳定的家庭成员会概率性地把一份自己的熟食搬入共享储物，
 * 供严重饥饿的已加载家庭成员随后自行取食。
 */
public final class NpcHouseholdCareGoal extends Goal {
    private static final int DONOR_MAX_HUNGER = 300;
    private static final int RECIPIENT_MIN_HUNGER = 650;
    private static final double RANGE_SQ = 64.0D * 64.0D;
    private static final double INTERACTION_RANGE_SQ = 9.0D;
    private static final long CONTRIBUTION_COOLDOWN = 600L;

    private enum Stage { LOAD_PRIVATE, DELIVER }

    private final NpcBase npc;
    private final MovementProgress movementProgress = new MovementProgress();
    @Nullable private HouseholdRecord household;
    @Nullable private NpcBase recipient;
    @Nullable private BlockPos privateStorage;
    @Nullable private BlockPos pantry;
    @Nullable private BlockPos approach;
    @Nullable private Stage stage;
    private int recalcPath;
    private int pathFailures;
    private boolean finished;

    public NpcHouseholdCareGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        clear();
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isSleeping()
                || npc.isPassenger() || npc.getTarget() != null
                || npc.getCommand() == NpcCommand.FOLLOW || npc.getCommand() == NpcCommand.STAY
                || (npc.tickCount - npc.getId()) % 100 != 0) return false;
        NpcLifeData lives = NpcLifeData.get(level.getServer());
        NpcLifeRecord life = lives.record(npc.getCharacterId());
        if (life == null) return false;
        NpcNeedsRecord ownNeeds = lives.ensureNeeds(npc.getCharacterId(), level.getGameTime());
        household = HouseholdData.get(level.getServer()).forMember(npc.getCharacterId());
        if (ownNeeds.hunger() > DONOR_MAX_HUNGER || household == null
                || household.members().size() < 2
                || !household.dimension().equals(level.dimension().location().toString())) return false;
        HouseholdCareRecord care = HouseholdData.get(level.getServer()).care(household.id());
        if (care != null && care.lastContributionAt() >= 0L
                && level.getGameTime() - care.lastContributionAt() < CONTRIBUTION_COOLDOWN) return false;
        pantry = household.sharedStorage();
        if (!validContainer(level, pantry) || pantry.distSqr(npc.blockPosition()) > RANGE_SQ) return false;
        recipient = household.members().stream()
                .filter(member -> !member.characterId().equals(npc.getCharacterId()))
                .map(member -> level.getEntity(member.characterId()))
                .filter(entity -> entity instanceof NpcBase target && target.isAlive())
                .map(entity -> (NpcBase) entity)
                .filter(target -> {
                    NpcNeedsRecord needs = lives.ensureNeeds(target.getUUID(), level.getGameTime());
                    return needs.hunger() >= RECIPIENT_MIN_HUNGER;
                })
                .max(Comparator.comparingInt(target -> lives.needs(target.getUUID()).hunger()))
                .orElse(null);
        if (recipient == null) return false;

        int carriedSlot = NpcFoodItems.findFoodSlot(npc.getEquipmentInventory(), false);
        if (carriedSlot >= 0) {
            stage = Stage.DELIVER;
        } else {
            privateStorage = life.privateStoragePosition();
            if (!validContainer(level, privateStorage)
                    || privateStorage.distSqr(npc.blockPosition()) > RANGE_SQ) return false;
            Container privateContainer = WarehouseContainers.asContainer(
                    level.getBlockEntity(privateStorage));
            int foodSlot = NpcFoodItems.findFoodSlot(privateContainer, false);
            if (foodSlot < 0 || !canAccept(npc.getEquipmentInventory(),
                    privateContainer.getItem(foodSlot))) return false;
            stage = Stage.LOAD_PRIVATE;
        }
        BlockPos target = currentTarget();
        approach = isInRange(target) ? npc.blockPosition() : InteractionApproach.find(npc, target);
        if (approach == null || !npc.requestMutex(AiMutex.SOCIAL)) {
            clear();
            return false;
        }
        NpcNeedsRecord recipientNeeds = lives.needs(recipient.getUUID());
        if (recipientNeeds == null
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsCare(
                npc, recipientNeeds.hunger())) {
            npc.releaseMutex(AiMutex.SOCIAL);
            clear();
            return false;
        }
        double urgency = recipientNeeds == null ? 0.0D : recipientNeeds.hunger() / 20.0D;
        if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.CARE, recipient.getUUID(),
                12.0D + urgency, npc.getCommand() == NpcCommand.WORK ? 8.0D : 2.0D,
                recipientNeeds != null && recipientNeeds.hunger() >= 900)) {
            npc.releaseMutex(AiMutex.SOCIAL);
            clear();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return !finished && stage != null && recipient != null && recipient.isAlive()
                && npc.isAlive() && npc.getTarget() == null;
    }

    @Override
    public void start() {
        recalcPath = 0;
        pathFailures = 0;
        finished = false;
        BlockPos target = currentTarget();
        if (approach != null) movementProgress.reset(npc, approach);
        npc.reportWorkActivity(stage == Stage.LOAD_PRIVATE ? "取出家庭援助食物" : "补充家庭共享食物",
                target, "准备照料 " + recipient.getNpcName());
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        if (!(npc.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }
        BlockPos target = currentTarget();
        if (target == null) {
            finished = true;
            return;
        }
        npc.getLookControl().setLookAt(target.getX() + 0.5D,
                target.getY() + 0.5D, target.getZ() + 0.5D);
        if (!InteractionApproach.readyToInteract(
                npc, target, approach, INTERACTION_RANGE_SQ)) {
            if (approach == null || movementProgress.tick(npc, approach)) {
                if (++pathFailures >= 3) {
                    npc.reportWorkActivity("家庭照料", target, "连续寻路失败，食物仍保留在原容器或背包");
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
        if (stage == Stage.LOAD_PRIVATE) loadFromPrivate(level);
        else deliverToPantry(level);
    }

    private void loadFromPrivate(ServerLevel level) {
        Container source = WarehouseContainers.asContainer(level.getBlockEntity(privateStorage));
        int slot = NpcFoodItems.findFoodSlot(source, false);
        SubjectRef character = SubjectRef.character(npc.getCharacterId());
        UUID sourceId = ContainerIds.block("private_storage",
                level.dimension().location().toString(), privateStorage);
        UUID targetId = ContainerIds.npcPersonal(npc.getCharacterId());
        TransferReceipt receipt = slot < 0 ? null : moveOne(level, source, slot, sourceId,
                character, AccessPolicy.PRIVATE, npc.getEquipmentInventory(), targetId,
                character, AccessPolicy.PRIVATE, TransferIntent.DELIVERY,
                "household-care-load:" + npc.getCharacterId());
        if (receipt == null || !receipt.successful()) {
            npc.reportWorkActivity("取出家庭援助食物", privateStorage,
                    receipt == null ? "私人储物食物失效" : receipt.reason());
            finished = true;
            return;
        }
        stage = Stage.DELIVER;
        pathFailures = 0;
        approach = isInRange(pantry) ? npc.blockPosition() : InteractionApproach.find(npc, pantry);
        if (approach == null) {
            // 食物已安全留在 NPC 背包，下次仍可继续贡献或自行食用。
            finished = true;
            return;
        }
        movementProgress.reset(npc, approach);
        recalcPath = 0;
        npc.reportWorkActivity("补充家庭共享食物", pantry,
                "已从私人储物取出一份食物，准备照料 " + recipient.getNpcName());
    }

    private void deliverToPantry(ServerLevel level) {
        Container target = WarehouseContainers.asContainer(level.getBlockEntity(pantry));
        int slot = NpcFoodItems.findFoodSlot(npc.getEquipmentInventory(), false);
        SubjectRef character = SubjectRef.character(npc.getCharacterId());
        SubjectRef family = SubjectRef.household(household.id());
        UUID sourceId = ContainerIds.npcPersonal(npc.getCharacterId());
        UUID targetId = ContainerIds.block("household_storage",
                level.dimension().location().toString(), pantry);
        TransferReceipt receipt = slot < 0 || target == null ? null : moveOne(level,
                npc.getEquipmentInventory(), slot, sourceId, character, AccessPolicy.PRIVATE,
                target, targetId, family, AccessPolicy.HOUSEHOLD, TransferIntent.GIFT,
                "household-care-gift:" + household.id() + ':' + npc.getCharacterId());
        if (receipt == null || !receipt.successful()) {
            npc.reportWorkActivity("补充家庭共享食物", pantry,
                    target == null ? "家庭共享容器失效"
                            : receipt == null ? "背包中已无可贡献食物" : receipt.reason());
            finished = true;
            return;
        }
        long now = level.getGameTime();
        HouseholdData.get(level.getServer()).recordFoodContribution(
                household.id(), npc.getCharacterId(), now);
        NpcLifeData lives = NpcLifeData.get(level.getServer());
        lives.remember(npc.getCharacterId(), new NpcMemoryRecord("household_care",
                recipient.getUUID(), "主动为 " + recipient.getNpcName() + " 补充家庭食物",
                45, 50, now, now + 72_000L), now);
        lives.remember(recipient.getUUID(), new NpcMemoryRecord("received_household_care",
                npc.getCharacterId(), npc.getNpcName() + " 为家庭补充了食物",
                55, 55, now, now + 96_000L), now);
        lives.adjustRelationship(recipient.getUUID(), npc.getCharacterId(),
                NpcRelationKind.DEPENDENT, 6, "在饥饿时获得家庭食物照料");
        lives.adjustRelationship(npc.getCharacterId(), recipient.getUUID(),
                NpcRelationKind.FAMILY, 2, "主动承担家庭供养");
        npc.playAction(NpcAction.TRADE_PRESENT, 30);
        npc.reportWorkActivity("家庭照料完成", pantry,
                "向共享储物放入一份食物，供 " + recipient.getNpcName() + " 取用");
        finished = true;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.releaseMutex(AiMutex.SOCIAL);
        clear();
        finished = false;
    }

    @Nullable
    private BlockPos currentTarget() {
        return stage == Stage.LOAD_PRIVATE ? privateStorage : pantry;
    }

    private boolean validContainer(ServerLevel level, @Nullable BlockPos position) {
        return position != null && level.hasChunkAt(position)
                && WarehouseContainers.asContainer(level.getBlockEntity(position)) != null;
    }

    private boolean isInRange(@Nullable BlockPos position) {
        return position != null && npc.distanceToSqr(position.getX() + 0.5D,
                position.getY() + 0.5D, position.getZ() + 0.5D) <= INTERACTION_RANGE_SQ;
    }

    private static boolean canAccept(Container target, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (int slot = 0; slot < target.getContainerSize(); slot++) {
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(target.getMaxStackSize(),
                    existing.getMaxStackSize())) return true;
        }
        return false;
    }

    @Nullable
    private TransferReceipt moveOne(ServerLevel level, @Nullable Container source, int sourceSlot,
                                    UUID sourceId, SubjectRef sourceOwner, AccessPolicy sourcePolicy,
                                    Container target, UUID targetId, SubjectRef targetOwner,
                                    AccessPolicy targetPolicy, TransferIntent intent, String authority) {
        if (source == null || sourceSlot < 0 || sourceSlot >= source.getContainerSize()) return null;
        ItemStack sourceStack = source.getItem(sourceSlot);
        if (sourceStack.isEmpty() || !canAccept(target, sourceStack)) return null;
        RightsStateData rights = RightsStateData.get(level.getServer());
        rights.ensureContext(sourceId, "household-care:source:" + sourceId,
                sourceOwner, sourceOwner, sourcePolicy);
        rights.ensureContext(targetId, "household-care:target:" + targetId,
                targetOwner, targetOwner, targetPolicy);
        long sourceRevision = rights.context(sourceId).revision();
        long targetRevision = rights.context(targetId).revision();
        UUID transferId = ContainerIds.named(authority + ':' + sourceRevision + ':'
                + targetRevision + ':' + level.getGameTime());
        TransferRequest request = new TransferRequest(transferId, sourceOwner, intent,
                List.of(new TransferLeg(sourceId, targetId, ItemFingerprint.of(sourceStack), 1)),
                List.of(new ExpectedRevision(sourceId, sourceRevision),
                        new ExpectedRevision(targetId, targetRevision)), authority,
                level.getGameTime());
        return new TransferService(rights, TransferLedgerData.get(level.getServer()))
                .submit(request, Map.of(sourceId, new TransferEndpoint(sourceId, source),
                        targetId, new TransferEndpoint(targetId, target)), level.getGameTime());
    }

    private void clear() {
        household = null;
        recipient = null;
        privateStorage = null;
        pantry = null;
        approach = null;
        stage = null;
    }
}
