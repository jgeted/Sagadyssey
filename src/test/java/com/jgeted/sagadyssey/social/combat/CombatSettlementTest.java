package com.jgeted.sagadyssey.social.combat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CombatSettlementTest {
    private CombatEncounter encounter(CombatEncounterData d,UUID who){return CombatEncounterService.start(d,UUID.randomUUID(),null,"minecraft:overworld",BlockPos.ZERO,32,
            List.of(new CombatSide(UUID.randomUUID(),"a",null,Set.of(who),EngagementRule.SELF_DEFENSE_ONLY),new CombatSide(UUID.randomUUID(),"b",null,Set.of(UUID.randomUUID()),EngagementRule.SELF_DEFENSE_ONLY)),0);}
    @Test void frozenSnapshotDoesNotAcquireNewTimestampOrFamilyOnReplay(){var d=new CombatEncounterData();var who=UUID.randomUUID();var e=encounter(d,who);var i=new CombatImpact(e.id(),who,UUID.randomUUID(),UUID.randomUUID(),null,1,2,3,true,200);
        d.freeze(e.id(),()->List.of(i));var reload=CombatEncounterData.load(d.save(new CompoundTag(),null),null);assertEquals(List.of(i),reload.freeze(e.id(),()->{throw new AssertionError("must not regenerate settlement");}));}
    @Test void frozenEncounterRejectsLateEvents(){var d=new CombatEncounterData();var who=UUID.randomUUID();var e=encounter(d,who);d.freeze(e.id(),()->List.of(new CombatImpact(e.id(),who,null,null,null,0,0,0,false,200)));
        assertThrows(IllegalStateException.class,()->CombatEncounterService.record(d,new CombatMoraleData(),e.id(),new EncounterEvent(UUID.randomUUID(),e.id(),EncounterEventType.SHOT,who,null,UUID.randomUUID(),201,"late")));}
    @Test void activeCharacterIndexSurvivesReloadAndReleasesEnded(){var d=new CombatEncounterData();var who=UUID.randomUUID();var e=encounter(d,who);assertEquals(e,CombatEncounterData.load(d.save(new CompoundTag(),null),null).activeFor(who,1));d.update(new CombatEncounter(e.id(),null,e.dimension(),e.center(),e.radius(),0,200,EncounterStatus.ENDED,e.sides(),e.events(),1));assertNull(d.activeFor(who,201));}
    @Test void duplicateEncountersRejectedOnReload(){var d=new CombatEncounterData();encounter(d,UUID.randomUUID());var tag=d.save(new CompoundTag(),null);var l=tag.getList("Encounters",Tag.TAG_COMPOUND);l.add(l.getCompound(0).copy());assertThrows(IllegalArgumentException.class,()->CombatEncounterData.load(tag,null));}
    @Test void orphanSettlementCannotInventEncounter(){var tag=new CompoundTag();tag.put("Encounters",new ListTag());var map=new HashMap<UUID,CombatImpact>();CombatImpact.put(map,new CombatImpact(UUID.randomUUID(),UUID.randomUUID(),null,null,null,1,0,0,false,5));tag.put("CombatImpacts",CombatImpact.save(map));assertThrows(IllegalArgumentException.class,()->CombatEncounterData.load(tag,null));}
}
