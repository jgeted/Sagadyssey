package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatEncounterBindingTest {
    private final CombatEncounterData data=new CombatEncounterData();
    private final UUID actor=UUID.randomUUID(),enemy=UUID.randomUUID(),army=UUID.randomUUID(),principal=UUID.randomUUID();
    private CombatEncounter start(){return CombatEncounterService.start(data,UUID.randomUUID(),null,"minecraft:overworld",BlockPos.ZERO,32,
            List.of(new CombatSide(UUID.randomUUID(),"defenders",null,Set.of(actor),EngagementRule.SELF_DEFENSE_ONLY),
                    new CombatSide(UUID.randomUUID(),"opponents",null,Set.of(enemy),EngagementRule.LETHAL_FORCE)),5);}
    private CombatEncounterBinding binding(CombatEncounter e){return new CombatEncounterBinding("sagadyssey:war_force",army,principal,e.id(),e.sideOf(actor).id());}
    @Test void schemaOneLoadsWithoutInventingBindingsAndWritesSchemaTwo(){var e=start();var tag=data.save(new CompoundTag(),null);tag.putInt("Schema",1);tag.remove("Bindings");var loaded=CombatEncounterData.load(tag,null);assertEquals(e,loaded.activeFor(actor,6));assertTrue(loaded.bindings(e.id()).isEmpty());assertEquals(2,loaded.save(new CompoundTag(),null).getIntOr("Schema",0));}
    @Test void bindingAndAdditionalMembersSurviveRestartAndPreserveWoundsAndSelfDefense(){
        var e=start();var event=new EncounterEvent(UUID.randomUUID(),e.id(),EncounterEventType.WOUNDED,actor,enemy,UUID.randomUUID(),6,"actual wound");
        e=CombatEncounterService.record(data,new CombatMoraleData(),e.id(),event);var b=binding(e);var ally=UUID.randomUUID();
        assertTrue(CombatEncounterService.enlist(data,e,b,Set.of(actor,ally),7));var other=UUID.randomUUID();
        assertTrue(CombatEncounterService.enlist(data,data.encounter(e.id()),b,Set.of(other),8));
        var loaded=CombatEncounterData.load(data.save(new CompoundTag(),null),null);var actual=loaded.encounter(e.id());
        assertEquals(List.of(event),actual.events());assertEquals(EngagementRule.SELF_DEFENSE_ONLY,actual.sideOf(ally).rule());
        assertEquals(b,loaded.binding(e.id(),b.ownerKind(),army));assertEquals(List.of(actual),loaded.hosted(b.ownerKind(),army,principal,9));
        assertEquals(actual,loaded.activeFor(other,9));assertTrue(loaded.hosted(b.ownerKind(),army,UUID.randomUUID(),9).isEmpty());
    }
    @Test void staleRosterAndCrossSideEditsHaveNoEffects(){var e=start();var b=binding(e);assertTrue(CombatEncounterService.enlist(data,e,b,Set.of(UUID.randomUUID()),6));var current=data.encounter(e.id());assertFalse(CombatEncounterService.enlist(data,e,b,Set.of(UUID.randomUUID()),7));assertFalse(CombatEncounterService.enlist(data,current,b,Set.of(enemy),7));assertEquals(current,data.encounter(e.id()));}
    @Test void conflictingHostIdentityIsRejectedBeforeRosterMutation(){var e=start();var b=binding(e);assertTrue(CombatEncounterService.enlist(data,e,b,Set.of(actor),6));var current=data.encounter(e.id());var conflict=new CombatEncounterBinding(b.ownerKind(),army,UUID.randomUUID(),e.id(),b.side());assertThrows(IllegalArgumentException.class,()->CombatEncounterService.enlist(data,current,conflict,Set.of(UUID.randomUUID()),7));assertEquals(current,data.encounter(e.id()));}
    @Test void cannotStealSomeoneFromAnotherActiveEncounter(){var e=start();var busy=UUID.randomUUID();CombatEncounterService.start(data,UUID.randomUUID(),null,e.dimension(),BlockPos.ZERO,16,List.of(new CombatSide(UUID.randomUUID(),"busy",null,Set.of(busy),EngagementRule.SELF_DEFENSE_ONLY),new CombatSide(UUID.randomUUID(),"other",null,Set.of(UUID.randomUUID()),EngagementRule.SELF_DEFENSE_ONLY)),6);assertFalse(CombatEncounterService.enlist(data,e,binding(e),Set.of(busy),7));assertNull(data.binding(e.id(),binding(e).ownerKind(),army));}
    @Test void frozenAndEndedRostersCannotGrow(){var e=start();data.freeze(e.id(),()->List.of(new CombatImpact(e.id(),actor,null,null,null,0,0,0,false,9)));assertFalse(CombatEncounterService.enlist(data,e,binding(e),Set.of(UUID.randomUUID()),10));data.update(new CombatEncounter(e.id(),null,e.dimension(),e.center(),e.radius(),5,9,EncounterStatus.ENDED,e.sides(),e.events(),1));assertNull(data.activeFor(actor,10));assertFalse(CombatEncounterService.enlist(data,data.encounter(e.id()),binding(e),Set.of(actor),10));}
    @Test void corruptedDuplicateOrOrphanBindingsAreRejected(){var e=start();assertTrue(CombatEncounterService.enlist(data,e,binding(e),Set.of(actor),6));var tag=data.save(new CompoundTag(),null);var bindings=(ListTag)tag.get("Bindings");bindings.add(bindings.getCompoundOrEmpty(0).copy());assertThrows(IllegalArgumentException.class,()->CombatEncounterData.load(tag,null));var orphan=data.save(new CompoundTag(),null);orphan.put("Encounters",new ListTag());assertThrows(IllegalArgumentException.class,()->CombatEncounterData.load(orphan,null));}
    @Test void activeLookupIsStableAcrossReloadEvenForLegacyOverlaps(){var first=start();var second=start();assertEquals(data.activeEncounters(actor,8),CombatEncounterData.load(data.save(new CompoundTag(),null),null).activeEncounters(actor,8));assertEquals(List.of(first,second).stream().min(Comparator.comparing(CombatEncounter::id)).orElseThrow(),data.activeFor(actor,8));}
}
