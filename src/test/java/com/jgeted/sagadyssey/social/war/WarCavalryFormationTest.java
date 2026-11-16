package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCavalryFormation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarCavalryTestSupport.*;

class WarCavalryFormationTest{
    @Test void everySupportedRosterHasDistinctHorseSizedSlotsInAllFourHeadings(){
        for(var target:List.of(FRONT,new WarIntent.Position(REAR.dimension(),-24,64,0),new WarIntent.Position(REAR.dimension(),0,64,24),new WarIntent.Position(REAR.dimension(),0,64,-24)))for(int size:new int[]{1,3,9,128}){
            var rear=new HashSet<WarIntent.Position>();var front=new HashSet<WarIntent.Position>();for(int slot=0;slot<size;slot++){assertTrue(rear.add(WarCavalryFormation.assembly(REAR,target,slot,size)));assertTrue(front.add(WarCavalryFormation.objective(REAR,target,slot,size)));}assertEquals(size,rear.size());
        }
    }
    @Test void exitWaypointsLeaveTheApproachLaneAndDoNotPointThroughTheEnemy(){
        for(int slot=0;slot<3;slot++){var front=WarCavalryFormation.objective(REAR,FRONT,slot,3);var exit=WarCavalryFormation.exit(REAR,FRONT,slot,3);assertTrue(exit.x()<front.x());assertEquals(6,Math.abs(exit.z()-front.z()));}
    }
    @Test void unsupportedDistanceHeightDimensionOrSlotsAreRejected(){
        for(var p:List.of(REAR,new WarIntent.Position(REAR.dimension(),50,64,0),new WarIntent.Position(REAR.dimension(),24,70,0),new WarIntent.Position(WarKey.parse("minecraft:the_nether"),24,64,0)))assertThrows(IllegalArgumentException.class,()->WarCavalryFormation.validate(REAR,p));assertThrows(IllegalArgumentException.class,()->WarCavalryFormation.assembly(REAR,FRONT,3,3));
    }
}
