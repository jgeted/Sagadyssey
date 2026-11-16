package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion.*;
import java.util.*;

/** Pure tactical rules. All numeric values are explicit prototype policy, replaceable at assembly. */
public final class WarCohesionPolicy implements com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Policy {
    public record Rules(int routMorale, int rallyMorale, int rejoinOrganization, int recovery,
                        long recoveryTicks, long maximumObservationGap, int woundedLoss, int killedLoss, int maximumShockRisk) {
        public Rules {
            if(routMorale<0 || rallyMorale<=routMorale || rallyMorale>100 || rejoinOrganization<1 || rejoinOrganization>10_000
                    || recovery<1 || recovery>10_000 || recoveryTicks<1 || maximumObservationGap<1
                    || woundedLoss<0 || killedLoss<woundedLoss || killedLoss>10_000 || maximumShockRisk<0 || maximumShockRisk>100)
                throw new IllegalArgumentException("invalid cohesion rules");
        }
        public static Rules prototype() { return new Rules(20,40,6000,200,40,40,4000,10_000,50); }
    }
    private final Rules rules;
    public WarCohesionPolicy(Rules rules) { this.rules=Objects.requireNonNull(rules); }
    public Rules rules() { return rules; }
    public long recoveryTicks() { return rules.recoveryTicks(); }
    public long maximumObservationGap() { return rules.maximumObservationGap(); }
    public WarCohesion plan(Optional<WarCohesion> previous, WarCohesionFrame frame, boolean recoveryEarned) {
        var old=previous.orElse(null);
        if(old!=null && (!old.unit().equals(frame.unit()) || !old.army().equals(frame.army()) || frame.now()<old.changedAt()
                || frame.sequence()<old.sequence() || frame.sequence()==old.sequence() && !frame.order().equals(old.order())))
            throw new IllegalArgumentException("stale or foreign cohesion observation");
        int organization=old==null?10_000:old.organization();
        var rally=old==null?frame.origin():old.rally();
        boolean newOrder=old==null || frame.sequence()>old.sequence();
        if(newOrder && frame.kind()!=WarCohesionFrame.OrderKind.COMBAT) rally=frame.target();
        var routed=new HashSet<UUID>(old==null?Set.of():old.routed());
        Map<UUID,Cursor> cursors=old==null?Map.of():old.cursors();
        Map<ImpactKey,Impact> impacts=old==null?Map.of():old.impacts();
        boolean cursorsChanged=false, impactsChanged=false;
        var characters=new HashSet<UUID>(); var contracts=new HashSet<UUID>();
        for(var member:frame.members()) { characters.add(member.character()); contracts.add(member.contract()); }
        routed.retainAll(contracts);
        for(var stream:frame.encounters().stream().sorted(Comparator.comparing(WarCohesionFrame.Events::encounter)).toList()) {
            var cursor=cursors.get(stream.encounter()); int from=cursor==null?0:cursor.count();
            if(from>stream.events().size() || from>0 && !cursor.last().equals(stream.events().get(from-1).id()))
                throw new IllegalArgumentException("encounter prefix changed; explicit reconciliation required");
            for(int i=from;i<stream.events().size();i++) {
                var event=stream.events().get(i);
                if(!characters.contains(event.character()) || event.shock()==WarCohesionFrame.Shock.NONE) continue;
                var key=new ImpactKey(stream.encounter(),event.character()); var prior=impacts.get(key);
                int severity=event.shock()==WarCohesionFrame.Shock.KILLED?2:1;
                if(prior==null || severity>prior.severity()) {
                    int strength=prior==null?frame.members().size():prior.originalStrength();
                    int weight=severity==2?rules.killedLoss():rules.woundedLoss();
                    if(event.occurredAt()>=frame.leadership().effectiveFrom())weight=weight*(100-frame.leadership().formationLossDiscount())/100;
                    int loss=Math.max(prior==null?0:prior.appliedLoss(),(weight+strength-1)/strength);
                    int increment=loss-(prior==null?0:prior.appliedLoss());
                    organization=Math.max(0,organization-increment);
                    if(!impactsChanged) { impacts=new HashMap<>(impacts); impactsChanged=true; }
                    impacts.put(key,new Impact(severity,strength,loss));
                    // Risk scales with the same normalized incremental casualty loss. More members/events do not multiply full-strength rolls.
                    if(increment>0) for(var member:frame.members()) if(member.available() && member.pressured()) {
                        long threshold=Math.min(1_000_000L,(long)(100-member.morale())*(10_000-organization)*rules.maximumShockRisk()*increment/(100L*Math.max(1,rules.woundedLoss())));
                        if(roll(frame.unit(),event.id(),member.contract())<threshold) routed.add(member.contract());
                    }
                }
            }
            if(from<stream.events().size()) {
                if(!cursorsChanged) { cursors=new HashMap<>(cursors); cursorsChanged=true; }
                cursors.put(stream.encounter(),new Cursor(stream.events().size(),stream.events().getLast().id()));
            }
        }
        for(var member:frame.members()) {
            if(member.available() && member.morale()<=rules.routMorale()) routed.add(member.contract());
            if(member.available() && member.atRally() && !member.pressured() && member.morale()>=rules.rallyMorale()) routed.remove(member.contract());
        }
        boolean broken=organization==0 || routed.size()*2L>=frame.members().size();
        boolean recovering=old!=null && (old.phase()==Phase.ROUTING || old.phase()==Phase.REGROUPING);
        Phase phase;
        int recovery=rules.recovery()+frame.leadership().rallyRecoveryBonus();
        if(broken || recovering) {
            phase=frame.safeAtRally()?Phase.REGROUPING:Phase.ROUTING;
            if(frame.safeAtRally() && recoveryEarned) organization=Math.min(10_000,organization+recovery);
            if(!broken && frame.safeAtRally() && organization>=rules.rejoinOrganization() && routed.isEmpty())
                phase=frame.kind()==WarCohesionFrame.OrderKind.COMBAT?Phase.STEADY:Phase.REGROUPING;
        } else if(frame.kind()==WarCohesionFrame.OrderKind.WITHDRAW) phase=frame.safeAtRally()?Phase.REGROUPING:Phase.WITHDRAWING;
        else if(frame.kind()==WarCohesionFrame.OrderKind.RALLY) phase=Phase.REGROUPING;
        else phase=organization<rules.rejoinOrganization()?Phase.WAVERING:Phase.STEADY;
        if(!broken && !recovering && frame.safeAtRally() && recoveryEarned && phase==Phase.REGROUPING) organization=Math.min(10_000,organization+recovery);
        if(old!=null && organization==old.organization() && phase==old.phase() && rally.equals(old.rally()) && frame.order().equals(old.order()) && frame.sequence()==old.sequence()
                && routed.equals(old.routed()) && cursors.equals(old.cursors()) && impacts.equals(old.impacts())) return old;
        return new WarCohesion(frame.unit(),frame.army(),old==null?1:Math.addExact(old.revision(),1),frame.now(),organization,phase,frame.order(),frame.sequence(),rally,routed,cursors,impacts);
    }
    private static int roll(UUID unit,UUID event,UUID contract) {
        long value=unit.getMostSignificantBits()^unit.getLeastSignificantBits()^event.getMostSignificantBits()^Long.rotateLeft(event.getLeastSignificantBits(),17)
                ^contract.getMostSignificantBits()^Long.rotateLeft(contract.getLeastSignificantBits(),31);
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L; value=(value^(value>>>27))*0x94d049bb133111ebL;
        return (int)Long.remainderUnsigned(value^(value>>>31),1_000_000);
    }
}
