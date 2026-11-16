package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** Durable cycle and one-contact fences. No horse/body copies, damage, morale or inventory authority. */
public record WarCavalry(UUID unit, UUID army, UUID order, long sequence, long revision, long changedAt,
                         long wave, Phase phase, long startedAt, long nextLaunchAt,
                         WarIntent.Position assembly, WarIntent.Position objective, List<WarTransmission.Member> members,
                         Set<UUID> spent, Set<UUID> returning) {
    public enum Phase { REFORM, RUN }
    public WarCavalry {
        Objects.requireNonNull(unit);Objects.requireNonNull(army);Objects.requireNonNull(order);Objects.requireNonNull(phase);
        Objects.requireNonNull(assembly);Objects.requireNonNull(objective);members=List.copyOf(members);spent=Set.copyOf(spent);returning=Set.copyOf(returning);
        if(sequence<1||revision<1||changedAt<0||wave<0||startedAt<0||startedAt>changedAt||nextLaunchAt<0||members.isEmpty()||members.size()>128||phase==Phase.RUN&&wave==0)
            throw new IllegalArgumentException("invalid cavalry counters/roster");
        var ids=new HashSet<UUID>();var people=new HashSet<UUID>();var bodies=new HashSet<UUID>();
        for(var m:members)if(!ids.add(m.contract())||!people.add(m.character())||!bodies.add(m.body()))throw new IllegalArgumentException("duplicate cavalry member");
        if(!ids.containsAll(spent)||!spent.containsAll(returning))throw new IllegalArgumentException("foreign cavalry fence");
        WarCavalryFormation.validate(assembly,objective);
    }
    public WarCavalry change(long now,Phase phase,long wave,long startedAt,long ready,Set<UUID> spent,Set<UUID> returning){
        return new WarCavalry(unit,army,order,sequence,Math.addExact(revision,1),now,wave,phase,startedAt,ready,assembly,objective,members,spent,returning);
    }
    public Set<UUID> contracts(){var ids=new HashSet<UUID>();for(var m:members)ids.add(m.contract());return Set.copyOf(ids);}
    public static boolean follows(WarCavalry a,WarCavalry b){
        if(a.equals(b))return true;
        if(!a.unit.equals(b.unit)||!a.army.equals(b.army)||a.revision==Long.MAX_VALUE||b.revision!=a.revision+1||b.changedAt<a.changedAt||b.sequence<a.sequence||b.nextLaunchAt<a.nextLaunchAt||b.wave<a.wave||b.wave-a.wave>1)return false;
        if(b.sequence>a.sequence)return b.phase==Phase.REFORM&&b.wave==a.wave;
        if(!a.order.equals(b.order)||!a.members.equals(b.members)||!a.assembly.equals(b.assembly)||!a.objective.equals(b.objective))return false;
        if(b.wave>a.wave)return a.phase==Phase.REFORM&&b.phase==Phase.RUN&&b.spent.isEmpty()&&b.returning.isEmpty()&&b.startedAt==b.changedAt;
        return b.spent.containsAll(a.spent)&&b.returning.containsAll(a.returning)&&b.startedAt==a.startedAt;
    }
}
