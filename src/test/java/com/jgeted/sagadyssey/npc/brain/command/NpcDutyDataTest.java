package com.jgeted.sagadyssey.npc.brain.command;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.npc.brain.command.NpcDutyData.*;

class NpcDutyDataTest {
    private Duty duty(){return new Duty(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Kind.MEDICAL,UUID.randomUUID(),List.of(),"minecraft:overworld",BlockPos.ZERO,28,100,1300,0,100,Status.OPEN,"explicit patient consent");}
    @Test void originalPatientWoundAndActualProgressRoundtrip(){var d=new NpcDutyData();var i=duty().tick(101).tick(2000);d.put(i);var loaded=NpcDutyData.load(d.save(new CompoundTag(),null),null);assertEquals(i,loaded.active(i.body()));assertEquals(2,i.worked());}
    @Test void repeatedAndBackwardsTickDoNotCountAsLabor(){var i=duty().tick(101);assertEquals(i,i.tick(101));assertEquals(i,i.tick(100));}
    @Test void workedNeverExceedsRequired(){var i=duty();for(int n=101;n<200;n++)i=i.tick(n);assertEquals(28,i.worked());}
    @Test void terminalResultCannotBeRevived(){var d=new NpcDutyData();var i=duty();d.put(i);d.put(i.update(Status.CANCELLED,0,102,"cancelled"));assertNull(d.active(i.body()));assertThrows(IllegalStateException.class,()->d.put(i));}
    @Test void patientIdentityCannotBeReplaced(){var d=new NpcDutyData();var i=duty();d.put(i);var other=new Duty(i.id(),i.body(),i.character(),UUID.randomUUID(),i.targetCharacter(),i.kind(),i.source(),i.proofs(),i.dimension(),i.anchor(),i.duration(),i.created(),i.expires(),0,101,i.status(),i.detail());assertThrows(IllegalStateException.class,()->d.put(other));}
    @Test void settlementCannotRewindToWork(){var d=new NpcDutyData();var i=duty();d.put(i.update(Status.SETTLING,28,150,"pending"));assertThrows(IllegalStateException.class,()->d.put(i.update(Status.OPEN,28,151,"retry")));}
    @Test void escortRequiresOriginalAuthorization(){var i=duty();assertThrows(IllegalArgumentException.class,()->new Duty(i.id(),i.body(),i.character(),i.targetBody(),i.targetCharacter(),Kind.ESCORT,i.source(),List.of(),i.dimension(),i.anchor(),100,100,700,0,100,Status.OPEN,"no proof"));}
    @Test void oldOrUnknownSchemaFailsClosed(){var t=new NpcDutyData().save(new CompoundTag(),null);t.putInt("Schema",99);assertThrows(IllegalArgumentException.class,()->NpcDutyData.load(t,null));t.remove("Schema");assertThrows(IllegalArgumentException.class,()->NpcDutyData.load(t,null));}
    @Test void malformedSavedConsentFieldsFailClosed(){var d=new NpcDutyData();d.put(duty());var t=d.save(new CompoundTag(),null);t.getList("Duties",Tag.TAG_COMPOUND).getCompound(0).remove("TargetCharacter");assertThrows(IllegalArgumentException.class,()->NpcDutyData.load(t,null));}
    @Test void duplicateIdentityRejectedOnDisk(){var d=new NpcDutyData();d.put(duty());var t=d.save(new CompoundTag(),null);var l=t.getList("Duties",Tag.TAG_COMPOUND);l.add(l.get(0).copy());assertThrows(IllegalArgumentException.class,()->NpcDutyData.load(t,null));}
    @Test void deadlinesAreFinite(){var i=duty();assertThrows(IllegalArgumentException.class,()->new Duty(i.id(),i.body(),i.character(),i.targetBody(),i.targetCharacter(),i.kind(),i.source(),i.proofs(),i.dimension(),i.anchor(),28,100,24101,0,100,Status.OPEN,"infinite"));}
    @Test void historyViewCannotMutateLedger(){var d=new NpcDutyData();d.put(duty());assertThrows(UnsupportedOperationException.class,()->d.all().clear());}
}
