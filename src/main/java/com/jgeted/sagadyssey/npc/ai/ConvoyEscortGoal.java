package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.command.PlayerCommandData;
import com.jgeted.sagadyssey.npc.command.SquadStrategy;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;

/** “护送运输”小队战术：战斗职业围绕同小队正在运输的 NPC 移动。 */
public final class ConvoyEscortGoal extends Goal {
    private static final double SEARCH_RANGE = 96.0D;
    private static final double DESIRED_DISTANCE_SQ = 16.0D;

    private final NpcBase npc;
    @Nullable private NpcBase carrier;
    private int recalcPath;

    public ConvoyEscortGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        if (npc.level().isClientSide || !npc.isAlive() || npc.getTarget() != null
                || !isGuardProfession(npc.getProfession()) || !NpcSchedulePlanner.allowsPatrol(npc)) return false;
        if ((npc.tickCount - npc.getId()) % 40 != 0) return false;
        carrier = findCarrier();
        return carrier != null && NpcAgencyDecision.choose(npc,
                NpcAgencyDecision.Action.PROTECT, carrier.getUUID(), 46.0D, 4.0D, false);
    }

    @Override
    public boolean canContinueToUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        return carrier != null && carrier.isAlive() && carrier.getLogisticsStage() > 0
                && npc.isAlive() && npc.getTarget() == null && isEscortStrategy()
                && NpcSchedulePlanner.allowsPatrol(npc);
    }

    @Override
    public void start() {
        recalcPath = 0;
        if (carrier != null) npc.syncLogisticsStatus(3, carrier.getNpcName(), 0, 0, 0);
    }

    @Override
    public void tick() {
        if (carrier == null) return;
        npc.getLookControl().setLookAt(carrier, 20.0F, 20.0F);
        if (npc.distanceToSqr(carrier) <= DESIRED_DISTANCE_SQ) {
            npc.getNavigation().stop();
            npc.clearLogisticsMoveTarget();
            return;
        }
        if (--recalcPath <= 0) {
            recalcPath = 15;
            BlockPos formation = formationPosition(carrier);
            npc.setLogisticsMoveTarget(formation);
            if (npc.isPassenger()) {
                npc.getNavigation().stop();
            } else {
                npc.getNavigation().moveTo(formation.getX() + 0.5D, formation.getY(),
                        formation.getZ() + 0.5D, 1.12D);
            }
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.clearLogisticsMoveTarget();
        npc.clearLogisticsStatus();
        carrier = null;
    }

    @Nullable
    private NpcBase findCarrier() {
        if (!(npc.getActiveOwner() instanceof ServerPlayer owner)) return null;
        PlayerCommandData data = CommandGroupAttachments.get(owner);
        data.registerMember(npc.getCharacterId(), npc.getNpcName());
        if (data.strategyOf(npc.getCharacterId()) != SquadStrategy.ESCORT_CONVOY) return null;
        String squad = data.squadOf(npc.getCharacterId());
        return npc.level().getEntitiesOfClass(NpcBase.class,
                        npc.getBoundingBox().inflate(SEARCH_RANGE), other -> other != npc && other.isAlive()
                                && other.isOwnedBy(owner.getUUID()) && other.getLogisticsStage() > 0
                                && (other.getProfession() == NpcProfession.COURIER
                                    || other.getProfession() == NpcProfession.TRADER))
                .stream()
                .filter(other -> {
                    data.registerMember(other.getCharacterId(), other.getNpcName());
                    return squad.equals(data.squadOf(other.getCharacterId()));
                })
                .min(Comparator.comparingDouble(npc::distanceToSqr))
                .orElse(null);
    }

    private boolean isEscortStrategy() {
        if (!(npc.getActiveOwner() instanceof ServerPlayer owner)) return false;
        return CommandGroupAttachments.get(owner).strategyOf(npc.getCharacterId()) == SquadStrategy.ESCORT_CONVOY;
    }

    private BlockPos formationPosition(NpcBase target) {
        int slot = Math.floorMod(npc.getCharacterId().hashCode(), 6);
        double angle = slot * (Math.PI * 2.0D / 6.0D);
        Vec3 offset = new Vec3(Math.cos(angle) * 3.0D, 0.0D, Math.sin(angle) * 3.0D);
        return BlockPos.containing(target.position().add(offset));
    }

    private static boolean isGuardProfession(NpcProfession profession) {
        return profession == NpcProfession.WARRIOR || profession == NpcProfession.ARCHER
                || profession == NpcProfession.HEAVY;
    }
}
