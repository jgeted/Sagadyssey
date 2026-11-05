package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcScheduleActivity;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.combat.MedicalService;
import com.jgeted.sagadyssey.social.combat.TreatmentType;
import com.jgeted.sagadyssey.social.combat.WoundRecord;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;

import java.util.Comparator;
import java.util.EnumSet;

/** 医师会寻找受伤盟友，走近后消耗一份白色羊毛作为绷带进行治疗。 */
public class MedicWorkGoal extends Goal {
    private static final double SEARCH_RANGE = 14.0D;
    private static final double TREAT_REACH_SQ = 6.25D;

    private final NpcBase npc;
    private LivingEntity patient;
    private int treatmentTicks;
    private boolean finished;

    public MedicWorkGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        if (!npc.isAlive() || npc.level().isClientSide
                || npc.getProfession() != NpcProfession.MEDIC
                || npc.getCommand() == NpcCommand.STAY
                || npc.getTarget() != null || npc.isPassenger()) return false;
        if ((npc.tickCount - npc.getId()) % 20 != 0 || !npc.hasBagItem(Items.WHITE_WOOL, 1)) return false;

        double range = npc.getCommand() == NpcCommand.FOLLOW ? 10.0D : SEARCH_RANGE;
        patient = npc.level().getEntitiesOfClass(LivingEntity.class,
                        npc.getBoundingBox().inflate(range),
                        living -> living.isAlive() && needsTreatment(living)
                                && (living == npc || npc.isCombatAlly(living)))
                .stream()
                .min(Comparator.comparingDouble(living -> living.getHealth() / living.getMaxHealth()))
                .orElse(null);
        if (patient == null) return false;
        boolean urgent = patient.getHealth() <= patient.getMaxHealth() * 0.40F;
        NpcScheduleActivity activity = NpcSchedulePlanner.current(npc).activity();
        if (!urgent && (activity == NpcScheduleActivity.REST
                || activity == NpcScheduleActivity.URGENT_NEEDS)) return false;
        return NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.CARE,
                patient.getUUID(), urgent ? 58.0D : 34.0D,
                npc.getHealth() < npc.getMaxHealth() * 0.5F ? 12.0D : 3.0D, urgent);
    }

    @Override
    public boolean canContinueToUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        return !finished && patient != null && patient.isAlive()
                && needsTreatment(patient)
                && npc.getTarget() == null && npc.getCommand() != NpcCommand.STAY
                && npc.distanceToSqr(patient) <= SEARCH_RANGE * SEARCH_RANGE
                && npc.hasBagItem(Items.WHITE_WOOL, 1);
    }

    @Override
    public void start() {
        treatmentTicks = 0;
        finished = false;
    }

    @Override
    public void tick() {
        npc.getLookControl().setLookAt(patient, 25.0F, npc.getMaxHeadXRot());
        if (npc.distanceToSqr(patient) > TREAT_REACH_SQ) {
            npc.getNavigation().moveTo(patient, 1.05D);
            treatmentTicks = 0;
            return;
        }
        npc.getNavigation().stop();
        treatmentTicks++;
        if (treatmentTicks == 1) npc.playAction(NpcAction.MEDIC_TEND, 32);
        if (treatmentTicks >= 28) {
            if (consumeTreatmentSupply()) {
                patient.heal(3.0F + npc.getNpcLevel());
                npc.addExperience(2);
            }
            finished = true;
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        patient = null;
        treatmentTicks = 0;
        finished = false;
    }

    private boolean needsTreatment(LivingEntity living) {
        if (living.getHealth() < living.getMaxHealth() * 0.85F) return true;
        if (living instanceof NpcBase other && npc.level() instanceof net.minecraft.server.level.ServerLevel level) {
            return InjuryData.get(level.getServer()).triage(other.getCharacterId(),
                    level.getGameTime()).activeWounds() > 0;
        }
        return false;
    }

    private boolean consumeTreatmentSupply() {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        RightsStateData rights = RightsStateData.get(level.getServer());
        SubjectRef healer = SubjectRef.character(npc.getCharacterId());
        java.util.UUID bagId = ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(bagId, "npc:" + npc.getCharacterId() + ":personal",
                healer, healer, AccessPolicy.PRIVATE);
        if (patient instanceof NpcBase other) {
            InjuryData injuries = InjuryData.get(level.getServer());
            WoundRecord wound = injuries.woundsOf(other.getCharacterId(), level.getGameTime())
                    .stream().filter(value -> value.status().active())
                    .max(java.util.Comparator.comparingInt(WoundRecord::bleeding)
                            .thenComparing(WoundRecord::severity)).orElse(null);
            if (wound != null) {
                try {
                    MedicalService.treat(injuries, rights, java.util.UUID.randomUUID(),
                            wound.id(), healer, bagId, npc.getPersonalInventory(),
                            TreatmentType.BANDAGE, level.getGameTime());
                    return true;
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    return false;
                }
            }
        }
        return new ConsumptionService(rights).consume(bagId, npc.getPersonalInventory(), healer,
                ItemFingerprint.of(new net.minecraft.world.item.ItemStack(Items.WHITE_WOOL)),
                1, "medical_first_aid", level.getGameTime()).successful();
    }
}
