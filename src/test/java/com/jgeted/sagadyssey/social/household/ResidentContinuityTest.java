package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.social.life.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ResidentContinuityTest {
    @Test void individualMembershipCommitIsAtomicAndReplayDoesNotDuplicate(){
        var h=new HouseholdData();var sourceHead=UUID.randomUUID();var targetHead=UUID.randomUUID();var person=UUID.randomUUID();
        var from=h.create("source","minecraft:overworld",UUID.randomUUID(),sourceHead,BlockPos.ZERO,0);
        var to=h.create("target","minecraft:overworld",UUID.randomUUID(),targetHead,BlockPos.ZERO,0);
        assertTrue(h.addMember(from.id(),person,HouseholdRole.KIN,1));var beforeSource=h.provisionPlan(from.id());var beforeTarget=h.provisionPlan(to.id());
        assertTrue(h.transferMember(person,from.id(),to.id(),10));assertFalse(h.transferMember(person,from.id(),to.id(),11));
        assertEquals(to.id(),h.forMember(person).id());assertFalse(h.byId(from.id()).contains(person));assertEquals(2,h.byId(to.id()).members().size());
        assertEquals(beforeSource,h.provisionPlan(from.id()));assertEquals(beforeTarget,h.provisionPlan(to.id()));
    }
    @Test void headOrWrongExpectedSourceCannotBeMoved(){
        var h=new HouseholdData();var head=UUID.randomUUID();var other=UUID.randomUUID();
        var a=h.create("a","minecraft:overworld",UUID.randomUUID(),head,BlockPos.ZERO,0);var b=h.create("b","minecraft:overworld",UUID.randomUUID(),other,BlockPos.ZERO,0);
        assertFalse(h.transferMember(head,a.id(),b.id(),1));assertFalse(h.transferMember(other,a.id(),b.id(),1));assertEquals(a.id(),h.forMember(head).id());assertEquals(b.id(),h.forMember(other).id());
    }
    @Test void relocationPreservesPersonHistoryOriginAndShiftButReplacesFallbackAndPrivatePointer(){
        var person=UUID.randomUUID();var origin=UUID.randomUUID();var target=UUID.randomUUID();
        var before=NpcLifeRecord.institution(person,"minecraft:overworld",origin,new BlockPos(1,1,1),"original",new BlockPos(2,1,1),new BlockPos(3,1,1),NpcWorkShift.NIGHT,0);
        var next=before.relocatedInstitution(target,new BlockPos(100,1,1),new BlockPos(101,1,1),100);
        assertEquals(person,next.npcId());assertEquals(origin,next.originSettlementId());assertEquals(target,next.institutionWorkplaceSettlementId());assertEquals(target,next.activeWorkplaceSettlementId());assertNull(next.privateStoragePosition());assertEquals(before.shift(),next.shift());assertEquals(before.courage(),next.courage());assertEquals(before.memories(),next.memories());
        assertEquals(target,next.recruited(UUID.randomUUID(),200).dismissed(300).activeWorkplaceSettlementId());
        assertEquals(next,NpcLifeRecord.load(next.save()));
    }
    @Test void emptyMoveLedgerIsAdditiveAndUnknownSchemaCannotBeSilentlyReset(){
        var d=new ResidentWorkMigrationData();var saved=d.save(new net.minecraft.nbt.CompoundTag(),null);
        assertTrue(ResidentWorkMigrationData.load(saved,null).applications().isEmpty());saved.putInt("Schema",2);assertThrows(IllegalArgumentException.class,()->ResidentWorkMigrationData.load(saved,null));
    }
}
