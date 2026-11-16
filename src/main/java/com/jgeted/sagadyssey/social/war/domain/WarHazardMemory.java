package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarIntent;
import java.util.*;

/** Bounded, replaceable intelligence. Old observations remain dated and confer no world-mutation authority. */
public record WarHazardMemory(UUID army,boolean avoid,List<WarHazardObservation> observations,long revision,long changedAt){
    public static final int MAX_REPORTS=128;
    private static final Comparator<WarHazardObservation> OLDEST_FIRST=Comparator.comparingLong(WarHazardObservation::observedAt).thenComparing(WarHazardObservation::id);
    public WarHazardMemory{
        Objects.requireNonNull(army);observations=List.copyOf(observations);
        if(revision<1||changedAt<0||observations.size()>MAX_REPORTS||observations.stream().anyMatch(o->!o.army().equals(army)||o.observedAt()>changedAt)
                ||observations.stream().map(WarHazardObservation::position).distinct().count()!=observations.size())throw new IllegalArgumentException("invalid army knowledge");
    }
    public static WarHazardMemory empty(UUID army,long now){return new WarHazardMemory(army,true,List.of(),1,now);}
    public WarHazardMemory policy(boolean enabled,long now){return enabled==avoid?this:copy(enabled,observations,now);}
    public WarHazardMemory observe(WarHazardObservation seen,long now){
        return survey(List.of(seen),Map.of(),now);
    }
    public WarHazardMemory clear(WarIntent.Position position,long now){var values=observations.stream().filter(o->!o.position().equals(position)).toList();return values.size()==observations.size()?this:copy(avoid,values,now);}
    public WarHazardMemory survey(List<WarHazardObservation> seen,Set<WarIntent.Position> clear,long now){
        var times=new HashMap<WarIntent.Position,Long>();clear.forEach(p->times.put(p,now));return survey(seen,times,now);
    }
    public WarHazardMemory survey(List<WarHazardObservation> seen,Map<WarIntent.Position,Long> clear,long now){
        var byPosition=new HashMap<WarIntent.Position,WarHazardObservation>();
        for(var old:observations)byPosition.put(old.position(),old);
        for(var entry:clear.entrySet()){
            if(entry.getValue()<0||entry.getValue()>now)throw new IllegalArgumentException("invalid clear observation time");
            var old=byPosition.get(entry.getKey());
            if(old!=null&&old.observedAt()<=entry.getValue())byPosition.remove(entry.getKey());
        }
        for(var observation:seen){
            if(!observation.army().equals(army)||observation.observedAt()>now)throw new IllegalArgumentException("foreign or future intelligence");
            long clearedAt=clear.getOrDefault(observation.position(),-1L);
            if(observation.observedAt()<clearedAt)continue;
            byPosition.merge(observation.position(),observation,(old,newer)->old.observedAt()>newer.observedAt()?old:newer);
        }
        var sorted=byPosition.values().stream().sorted(OLDEST_FIRST).toList();
        var retained=sorted.subList(Math.max(0,sorted.size()-MAX_REPORTS),sorted.size());
        return retained.equals(observations)?this:copy(avoid,retained,now);
    }
    public boolean blocks(WarIntent.Position feet){return avoid&&observations.stream().anyMatch(o->o.position().dimension().equals(feet.dimension())&&o.position().x()==feet.x()&&o.position().z()==feet.z()&&feet.y()>=o.position().y()-1&&feet.y()<=o.position().y()+3);}
    private WarHazardMemory copy(boolean policy,List<WarHazardObservation> values,long now){if(now<changedAt||revision==Long.MAX_VALUE)throw new IllegalArgumentException("knowledge clock reversed");return new WarHazardMemory(army,policy,values,revision+1,now);}
    public static boolean follows(WarHazardMemory a,WarHazardMemory b){return a.equals(b)||a.army.equals(b.army)&&b.revision==a.revision+1&&b.changedAt>=a.changedAt;}
}
