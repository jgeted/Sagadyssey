package com.jgeted.sagadyssey.npc.brain.command;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NpcDutyEvidenceDataTest {
    private NpcDutyEvidenceData.Evidence proof(){return new NpcDutyEvidenceData.Evidence(UUID.randomUUID(),NpcDutyData.Kind.MEDICAL,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),100,false);}
    @Test void originalConsentSurvivesSave(){var d=new NpcDutyEvidenceData();var e=proof();d.put(e);assertEquals(e,NpcDutyEvidenceData.load(d.save(new CompoundTag(),null),null).find(e.duty()));}
    @Test void originalPatientMayRevokeAndRevocationSurvives(){var d=new NpcDutyEvidenceData();var e=proof();d.put(e);assertTrue(d.revoke(e.principal(),e.duty()));assertTrue(NpcDutyEvidenceData.load(d.save(new CompoundTag(),null),null).find(e.duty()).revoked());}
    @Test void EmployerAndDoctorCannotOverridePatient(){var d=new NpcDutyEvidenceData();var e=proof();d.put(e);assertFalse(d.revoke(e.agent(),e.duty()));assertFalse(d.find(e.duty()).revoked());}
    @Test void cannotSubstituteNewProofOrReviveConsent(){var d=new NpcDutyEvidenceData();var e=proof();d.put(e);d.revoke(e.principal(),e.duty());assertThrows(IllegalStateException.class,()->d.put(e));}
    @Test void missingRevocationStateFailsClosed(){var d=new NpcDutyEvidenceData();d.put(proof());var tag=d.save(new CompoundTag(),null);tag.getList("Evidence",Tag.TAG_COMPOUND).getCompound(0).remove("Revoked");assertThrows(IllegalArgumentException.class,()->NpcDutyEvidenceData.load(tag,null));}
    @Test void duplicateProofDoesNotSilentlyOverwrite(){var d=new NpcDutyEvidenceData();d.put(proof());var tag=d.save(new CompoundTag(),null);var list=tag.getList("Evidence",Tag.TAG_COMPOUND);list.add(list.getCompound(0).copy());assertThrows(IllegalArgumentException.class,()->NpcDutyEvidenceData.load(tag,null));}
}
