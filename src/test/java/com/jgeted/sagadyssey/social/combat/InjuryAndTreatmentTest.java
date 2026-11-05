package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InjuryAndTreatmentTest {
    @Test
    void untreatedBleedingProgressesButBandagingConsumesRealSupplyAndStabilizes() {
        InjuryData injuries = new InjuryData();
        UUID patient = UUID.randomUUID();
        UUID woundId = UUID.randomUUID();
        WoundRecord original = InjuryService.recordHit(injuries, woundId, patient,
                UUID.randomUUID(), WoundType.CUT, 8.0F, 0.0D, 0L, UUID.randomUUID());
        assertEquals(WoundSeverity.SEVERE, original.severity());

        WoundRecord progressed = original.progress(6_000L);
        assertTrue(progressed.trauma() > original.trauma());
        assertTrue(progressed.infection() >= original.infection());

        RightsStateData rights = new RightsStateData();
        SubjectRef healer = SubjectRef.character(UUID.randomUUID());
        UUID bagId = UUID.randomUUID();
        SimpleContainer bag = new SimpleContainer(2);
        bag.setItem(0, new ItemStack(Items.WHITE_WOOL));
        rights.ensureContext(bagId, "medic", healer, healer, AccessPolicy.PRIVATE);
        TreatmentRecord treatment = MedicalService.treat(injuries, rights, UUID.randomUUID(),
                woundId, healer, bagId, bag, TreatmentType.BANDAGE, 100L);

        assertTrue(treatment.successful());
        assertEquals(0, count(bag, Items.WHITE_WOOL));
        WoundRecord bandaged = injuries.wound(woundId);
        assertTrue(bandaged.bleeding() < original.bleeding());
        assertTrue(bandaged.status() == WoundStatus.STABILIZED
                || bandaged.status() == WoundStatus.HEALING);

        int revision = bandaged.revision();
        assertThrows(IllegalStateException.class, () -> MedicalService.treat(injuries, rights,
                UUID.randomUUID(), woundId, healer, bagId, bag, TreatmentType.BANDAGE, 200L));
        assertEquals(revision, injuries.wound(woundId).revision());
    }

    @Test
    void legTraumaProducesMobilityLossAndCriticalWoundsDriveTriage() {
        InjuryData injuries = new InjuryData();
        UUID patient = UUID.randomUUID();
        UUID chosen = findRegion(BodyRegion.LEFT_LEG);
        InjuryService.recordHit(injuries, chosen, patient, null,
                WoundType.FRACTURE, 12.0F, 0.0D, 0L, null);

        TriageAssessment triage = injuries.triage(patient, 1L);
        assertEquals(1, triage.activeWounds());
        assertTrue(triage.mobilityPenalty() > 0);
        assertTrue(triage.mortalDanger());
        assertTrue(triage.incapacitated());
    }

    private static UUID findRegion(BodyRegion sought) {
        for (int value = 0; value < 10_000; value++) {
            UUID id = new UUID(0L, value);
            int index = Math.floorMod((int) (id.getMostSignificantBits()
                    ^ id.getLeastSignificantBits()), BodyRegion.values().length);
            if (BodyRegion.values()[index] == sought) return id;
        }
        throw new AssertionError("no deterministic wound id for region");
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) result += container.getItem(slot).getCount();
        }
        return result;
    }
}
