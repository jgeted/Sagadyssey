package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import java.util.*;

final class WarCohesionTestSupport {
    static UUID id(long n) { return new UUID(0,n); }
    static final WarIntent.Position POINT=new WarIntent.Position(WarKey.parse("test:world"),0,64,0);
    static List<WarCohesionFrame.Member> members(int count,int morale,boolean safe) {
        var result=new ArrayList<WarCohesionFrame.Member>();
        for(int i=0;i<count;i++) result.add(new WarCohesionFrame.Member(id(100+i),id(200+i),id(300+i),true,morale,safe,!safe)); return result;
    }
    static WarCohesionFrame frame(long now,List<WarCohesionFrame.Member> members,List<WarCohesionFrame.Event> events) {
        return new WarCohesionFrame(id(1),id(2),id(3),1,WarCohesionFrame.OrderKind.COMBAT,POINT,POINT,now,members,
                events.isEmpty()?List.of():List.of(new WarCohesionFrame.Events(id(4),events)));
    }
    static WarCohesionFrame command(WarCohesionFrame base,long sequence,WarCohesionFrame.OrderKind kind) {
        return new WarCohesionFrame(base.unit(),base.army(),id(10+sequence),sequence,kind,POINT,POINT,base.now(),base.members(),base.encounters());
    }
    static WarCohesionFrame.Event wound(long event,int member,long now) { return new WarCohesionFrame.Event(id(event),id(200+member),WarCohesionFrame.Shock.WOUNDED,now); }
    static WarCohesion state(int organization,WarCohesion.Phase phase) {
        return new WarCohesion(id(1),id(2),1,0,organization,phase,id(3),1,POINT,Set.of(),Map.of(),Map.of());
    }
}
