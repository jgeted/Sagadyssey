package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import java.util.*;

/** Explicit optional sword sortie. No kinetic damage bonus and no spear execution. */
public final class WarCavalryPolicy implements Policy{
    public record Rules(int minimumOrganization,long reformTicks,long recoveryTicks,long runLimit,double minimumRun,double minimumSpeed,long motionAge){
        public Rules{if(minimumOrganization<0||minimumOrganization>10000||reformTicks<1||recoveryTicks<1||runLimit<1||!Double.isFinite(minimumRun)||minimumRun<0||!Double.isFinite(minimumSpeed)||minimumSpeed<0||motionAge<0)throw new IllegalArgumentException("invalid cavalry rules");}
        public static Rules prototype(){return new Rules(6000,40,200,400,6,.05,5);}
    }
    private final Rules rules;
    public WarCavalryPolicy(Rules rules){this.rules=Objects.requireNonNull(rules);}
    public long reformTicks(){return rules.reformTicks();}
    public long maximumObservationGap(){return 40;}
    public boolean permits(Contact c,long now){return c.requestedAt()==now&&now-c.movingAt()<=rules.motionAge()&&c.distance()>=rules.minimumRun()&&c.speed()>=rules.minimumSpeed()&&c.alignment()>=.5;}
    public WarCavalry plan(Optional<WarCavalry> previous,WarCavalryFrame f,boolean reformed,boolean interrupted){
        var a=previous.orElse(null);var roster=f.members().stream().map(m->new WarTransmission.Member(m.contract(),m.character(),m.body())).toList();
        if(a!=null&&(!a.unit().equals(f.unit())||!a.army().equals(f.army())||f.sequence()<a.sequence()||f.now()<a.changedAt()||f.sequence()==a.sequence()&&!f.order().equals(a.order())))throw new IllegalArgumentException("stale cavalry frame");
        if(a==null||f.sequence()>a.sequence())return new WarCavalry(f.unit(),f.army(),f.order(),f.sequence(),a==null?1:Math.addExact(a.revision(),1),f.now(),a==null?0:a.wave(),WarCavalry.Phase.REFORM,a==null?0:a.startedAt(),a==null?0:Math.max(a.nextLaunchAt(),a.phase()==WarCavalry.Phase.RUN?add(f.now(),rules.recoveryTicks()):0),f.assembly(),f.objective(),roster,Set.of(),Set.of());
        if(!roster.equals(a.members())||!f.assembly().equals(a.assembly())||!f.objective().equals(a.objective()))throw new IllegalArgumentException("cavalry roster/geometry changed without a new order");
        if(interrupted)return a.change(f.now(),WarCavalry.Phase.REFORM,a.wave(),a.startedAt(),Math.max(a.nextLaunchAt(),add(f.now(),rules.recoveryTicks())),a.contracts(),a.contracts());
        boolean eligible=f.allEligible()&&f.organization()>=rules.minimumOrganization();
        if(a.phase()==WarCavalry.Phase.REFORM){
            if(eligible&&f.assembled()&&reformed&&f.now()>=a.nextLaunchAt())return a.change(f.now(),WarCavalry.Phase.RUN,Math.addExact(a.wave(),1),f.now(),add(f.now(),rules.recoveryTicks()),Set.of(),Set.of());
            return a;
        }
        var spent=new HashSet<>(a.spent());var returning=new HashSet<>(a.returning());
        if(!eligible||f.now()-a.startedAt()>=rules.runLimit()){spent.addAll(a.contracts());returning.addAll(a.contracts());}
        for(var m:f.members())if(spent.contains(m.contract())&&m.atExit())returning.add(m.contract());
        var phase=spent.size()==a.members().size()&&f.assembled()?WarCavalry.Phase.REFORM:WarCavalry.Phase.RUN;
        if(spent.equals(a.spent())&&returning.equals(a.returning())&&phase==a.phase())return a;
        return a.change(f.now(),phase,a.wave(),a.startedAt(),a.nextLaunchAt(),spent,returning);
    }
    private static long add(long a,long b){return Math.addExact(a,b);}
}
