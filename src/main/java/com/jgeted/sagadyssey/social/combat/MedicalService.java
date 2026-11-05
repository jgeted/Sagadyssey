package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Applies treatment only after the corresponding physical medical supply is consumed. */
public final class MedicalService {
    private MedicalService() { }

    public static TreatmentRecord treat(InjuryData injuries, RightsStateData rights,
                                        UUID treatmentId, UUID woundId,
                                        SubjectRef healer, UUID supplyContainerId,
                                        Container supplies, TreatmentType type, long now) {
        TreatmentRecord replay = injuries.treatment(treatmentId);
        if (replay != null) return replay;
        WoundRecord wound = injuries.wound(woundId);
        if (wound == null) throw new IllegalStateException("伤口不存在");
        // Validate compatibility before consuming anything.
        if (type == TreatmentType.SPLINT && wound.type() != WoundType.FRACTURE
                && wound.type() != WoundType.BLUNT) {
            throw new IllegalArgumentException("夹板不适用于这种伤口");
        }
        ItemFingerprint supply = ItemFingerprint.of(new ItemStack(type.supply()));
        ConsumptionService.Result consumed = new ConsumptionService(rights).consume(
                supplyContainerId, supplies, healer, supply, type.quantity(),
                "treatment:" + treatmentId, now);
        if (!consumed.successful()) {
            throw new IllegalStateException("医疗物资无法使用：" + consumed.reason());
        }
        WoundRecord updated = wound.treat(type, now);
        TreatmentRecord treatment = new TreatmentRecord(treatmentId, woundId, healer,
                type, now, supplyContainerId, true, "已消耗实体医疗物资");
        injuries.recordTreatment(treatment, updated);
        return treatment;
    }
}
