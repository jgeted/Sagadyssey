package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WarHazardMemoryTest{
    static WarHazardObservation seen(UUID army,int x,long tick){return new WarHazardObservation(UUID.randomUUID(),army,UUID.randomUUID(),UUID.randomUUID(),WarEngineeringProgramTest.p(x,10,0),WarKey.parse("sagadyssey:war/hazard/spike"),"native spike",tick,WarHazardObservation.Source.SURVEY);}
    @Test void intelligenceIsArmyLocalAndDoesNotGrantAnyWorldAuthority(){var army=UUID.randomUUID();var m=WarHazardMemory.empty(army,100);assertThrows(IllegalArgumentException.class,()->m.observe(seen(UUID.randomUUID(),0,101),101));assertFalse(m.blocks(WarEngineeringProgramTest.p(0,11,0)));}
    @Test void optionalCautionAndHeadFootprintAreIndependentOfPhysicalHazards(){var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100).observe(seen(a,0,101),101);assertTrue(m.blocks(WarEngineeringProgramTest.p(0,11,0)));assertFalse(m.blocks(WarEngineeringProgramTest.p(0,15,0)));assertFalse(m.blocks(WarEngineeringProgramTest.p(1,11,0)));assertFalse(m.policy(false,102).blocks(WarEngineeringProgramTest.p(0,11,0)));assertEquals(m.observations(),m.policy(false,102).observations());}
    @Test void olderReportsAndRepeatedReceiptsCannotReplaceNewerKnowledge(){var a=UUID.randomUUID();var o=seen(a,0,102);var m=WarHazardMemory.empty(a,100).observe(o,102);assertSame(m,m.observe(o,103));assertSame(m,m.observe(seen(a,0,101),103));}
    @Test void recordsEvictOldestAtTheBoundWithoutGrowing(){var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100);for(int i=0;i<140;i++)m=m.observe(seen(a,i,101+i),101+i);assertEquals(128,m.observations().size());assertEquals(12,m.observations().getFirst().position().x());}
    @Test void completedSurveyPublishesOneRevisionAndVisibleClearLeavesHiddenMarks(){var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100).observe(seen(a,0,101),101).observe(seen(a,1,101),101);var n=m.survey(List.of(seen(a,2,102),seen(a,3,102)),Set.of(WarEngineeringProgramTest.p(0,10,0)),102);assertEquals(m.revision()+1,n.revision());assertTrue(WarHazardMemory.follows(m,n));assertEquals(Set.of(1,2,3),new HashSet<>(n.observations().stream().map(o->o.position().x()).toList()));}
    @Test void futureReportsAndReverseClocksFailClosed(){var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100);assertThrows(IllegalArgumentException.class,()->m.observe(seen(a,0,101),100));assertThrows(IllegalArgumentException.class,()->m.policy(false,99));}
    @Test void aSlowSurveyCannotEraseAReportMadeAfterItsClearObservation(){var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100).observe(seen(a,0,104),104);assertSame(m,m.survey(List.of(),Map.of(WarEngineeringProgramTest.p(0,10,0),102L),105));}
}
