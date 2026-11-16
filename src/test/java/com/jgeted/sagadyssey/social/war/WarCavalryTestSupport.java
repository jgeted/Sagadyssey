package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import java.util.*;

final class WarCavalryTestSupport{
    static UUID id(int n){return new UUID(0,n);}
    static final WarIntent.Position REAR=new WarIntent.Position(WarKey.parse("minecraft:overworld"),0,64,0),FRONT=new WarIntent.Position(REAR.dimension(),24,64,0);
    static WarCavalryFrame frame(long now,boolean eligible,boolean rear,boolean exit){return new WarCavalryFrame(id(1),id(2),id(3),1,now,REAR,FRONT,10000,java.util.stream.IntStream.range(0,3).mapToObj(i->new WarCavalryFrame.Member(id(10+i),id(20+i),id(30+i),eligible,rear,exit)).toList());}
    static WarCavalryFrame order(WarCavalryFrame f,long sequence){return new WarCavalryFrame(f.unit(),f.army(),id(100+(int)sequence),sequence,f.now(),f.assembly(),f.objective(),f.organization(),f.members());}
    static WarCavalryFrame organization(WarCavalryFrame f,int score){return new WarCavalryFrame(f.unit(),f.army(),f.order(),f.sequence(),f.now(),f.assembly(),f.objective(),score,f.members());}
    static final class Memory implements Journal{
        final Map<UUID,WarCavalry> saved=new HashMap<>();boolean pending,failed,bad,thrown;int writes;
        public Collection<WarCavalry> recover(){return List.copyOf(saved.values());}
        public Written persist(WarCavalry v){writes++;if(thrown)throw new IllegalStateException("IO");if(failed)return new Written(Status.UNKNOWN,Optional.empty(),"IO");if(pending)return new Written(Status.PENDING,Optional.empty(),"");if(bad)return new Written(Status.APPLIED,Optional.empty(),"");var a=saved.get(v.unit());if(a!=null&&!WarCavalry.follows(a,v))return new Written(Status.UNKNOWN,Optional.empty(),"revision");saved.put(v.unit(),v);return new Written(Status.APPLIED,Optional.of(v),"");}
        public void close(){}
    }
    static WarCavalryService service(Memory m){return new WarCavalryService(m,new WarCavalryPolicy(WarCavalryPolicy.Rules.prototype()));}
    static WarCavalry run(WarCavalryService s){for(int i=0;i<=40;i+=20)s.advance(frame(i,true,true,false));return s.view(id(1)).state().orElseThrow();}
    static Contact contact(long now){return new Contact(id(80),id(81),now,now,10,.25,1);}
    static WarCavalry spent(){var s=service(new Memory());var v=run(s);for(var m:v.members())s.finish(v.unit(),v.order(),v.wave(),m,Optional.empty(),60);return s.view(v.unit()).state().orElseThrow();}
}
