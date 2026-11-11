package com.jgeted.sagadyssey.npc.ai;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;

/** 只回收由该 NPC 自己产生并标记的溢出物，不抢取玩家或其他居民的物品。 */
public final class NpcGroundItemRecoveryGoal extends Goal {
    private final NpcBase npc;
    @Nullable private ItemEntity target;

    public NpcGroundItemRecoveryGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.level().isClientSide() || npc.isPassenger()
                || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null
                || !isProducer() || !NpcSchedulePlanner.allowsWork(npc)
                || (npc.tickCount - npc.getId()) % 20 != 0) return false;
        UUID id = npc.getCharacterId();
        target = npc.level().getEntitiesOfClass(ItemEntity.class, npc.getBoundingBox().inflate(10.0D),
                        item -> item.isAlive() && item.getPersistentData().read(NpcBase.WORK_OUTPUT_OWNER_TAG, UUIDUtil.CODEC).isPresent()
                                && id.equals(item.getPersistentData().read(NpcBase.WORK_OUTPUT_OWNER_TAG, UUIDUtil.CODEC).orElseThrow())
                                && npc.isWorkAllowedAt(item.blockPosition())
                                && npc.hasBagSpaceFor(item.getItem().copy()))
                .stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return target != null && target.isAlive() && npc.isAlive() && !npc.isPassenger()
                && npc.getCommand() == NpcCommand.WORK && npc.getTarget() == null
                && NpcSchedulePlanner.allowsWork(npc);
    }

    @Override
    public void start() {
        if (target != null) {
            npc.getNavigation().moveTo(target, 1.05D);
            npc.reportWorkActivity("回收本人溢出物", target.blockPosition(),
                    target.getItem().getCount() + " × " + target.getItem().getHoverName().getString());
        }
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        if (target == null) return;
        npc.getLookControl().setLookAt(target, 25.0F, npc.getMaxHeadXRot());
        if (npc.distanceToSqr(target) > 2.25D) {
            if (npc.tickCount % 20 == 0) npc.getNavigation().moveTo(target, 1.05D);
            return;
        }
        if (npc.addToBag(target.getItem())) target.discard();
        target = null;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        target = null;
    }

    private boolean isProducer() {
        return npc.getProfession() == NpcProfession.FARMER
                || npc.getProfession() == NpcProfession.WORKER
                || npc.getProfession() == NpcProfession.BLACKSMITH;
    }
}
