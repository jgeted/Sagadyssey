package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarTransmissionTest {
    @Test void localAndCourierTimingAreDeterministicAndCannotBeShortened(){
        var origin=WarDispatchServiceTest.point(0);assertEquals(5,WarTransmission.route(origin,WarDispatchServiceTest.point(32),0,0).delayTicks());
        var courier=WarTransmission.route(origin,WarDispatchServiceTest.point(80),10,20);assertEquals(WarTransmission.Channel.COURIER,courier.channel());assertEquals(140,courier.dueAt());
        assertThrows(IllegalArgumentException.class,()->new WarTransmission.Route(origin,WarDispatchServiceTest.point(80),0,0,5,WarTransmission.Channel.DIRECT));
    }
    @Test void staleCrossDimensionFarOrExhaustedClockRoutesCannotBecomeInstantCommands(){
        var origin=WarDispatchServiceTest.point(0);assertThrows(IllegalArgumentException.class,()->WarTransmission.route(origin,origin,0,1201));
        assertThrows(IllegalArgumentException.class,()->WarTransmission.route(origin,new WarIntent.Position(WarKey.parse("minecraft:the_nether"),0,64,0),0,0));
        assertThrows(IllegalArgumentException.class,()->WarTransmission.route(origin,WarDispatchServiceTest.point(1025),0,0));
        assertThrows(IllegalArgumentException.class,()->WarTransmission.route(origin,origin,Long.MAX_VALUE,Long.MAX_VALUE));
    }
    @Test void rosterSlotsAreStableUniqueAndCannotBeAppliedToOtherBodies(){
        var f=new WarDispatchServiceTest.Fixture();var members=new ArrayList<>(f.members);members.add(new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));members.add(new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
        var value=new WarTransmission(f.order(),1,f.route(0),members,Optional.empty());assertEquals(3,value.members().stream().map(value::station).distinct().count());
        var position=value.station(members.getFirst());Collections.reverse(members);assertEquals(position,value.station(f.members.getFirst()));
        assertThrows(IllegalArgumentException.class,()->value.station(new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())));
    }
}
