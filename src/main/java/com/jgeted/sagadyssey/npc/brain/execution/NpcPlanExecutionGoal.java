package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** PLANNED 模式唯一的非战斗 Goal；旧模式下完全不参与竞争。 */
public final class NpcPlanExecutionGoal extends Goal {
    private final NpcBase npc;

    public NpcPlanExecutionGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return shouldRun();
    }

    @Override
    public boolean canContinueToUse() {
        return shouldRun();
    }

    @Override
    public void start() {
        npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        NpcBrainRuntime.tick(npc);
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        if (!NpcBrainRuntime.isPlanned(npc) || MinecraftApi.server(npc) == null
                || NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).isEmpty()) return;
        if (npc.getTarget() != null && npc.getTarget().isAlive()) {
            NpcBrainRuntime.cancel(npc, PlanCancellationReason.THREAT,
                    "发现战斗威胁，取消非战斗计划");
        } else if (npc.isInWater()
                || npc.getHealth() <= npc.getMaxHealth() * 0.25F) {
            NpcBrainRuntime.cancel(npc, PlanCancellationReason.URGENT_NEED,
                    "生存状态抢占，取消非战斗计划");
        }
    }

    private boolean shouldRun() {
        return !npc.level().isClientSide()
                && NpcBrainRuntime.isPlanned(npc)
                && (NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).isPresent()
                || urgentHunger()
                || com.jgeted.sagadyssey.npc.brain.command.NpcCommandData.get(MinecraftApi.server(npc)).active(npc.getCharacterId()).isPresent()
                || com.jgeted.sagadyssey.npc.brain.command.NpcCommandData.get(MinecraftApi.server(npc)).queued(npc.getCharacterId()).isPresent()
                || com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.eligible(npc)
                || npc.getCommand() == com.jgeted.sagadyssey.npc.entity.NpcCommand.WORK
                && (com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkDiscovery.producer(npc)
                || npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.BLACKSMITH
                || com.jgeted.sagadyssey.social.needs.SupplyTransportTask.eligible(npc)
                || com.jgeted.sagadyssey.social.religion.ReligionTask.eligible(npc)
                || com.jgeted.sagadyssey.social.religion.life.ChurchLifeTask.eligible(npc)
                || com.jgeted.sagadyssey.npc.brain.life.LifePlanProvider.eligible(npc)))
                && (npc.getTarget() == null || !npc.getTarget().isAlive());
    }

    /** Wake the existing survival chooser without changing a standing command or granting food rights. */
    private boolean urgentHunger() {
        if (!npc.isAlive()) return false;
        var life = com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(npc));
        var needs = life.needs(npc.getCharacterId());
        return life.record(npc.getCharacterId()) != null && needs != null && needs.hunger() >= 800;
    }
}
