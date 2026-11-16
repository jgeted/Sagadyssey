package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import java.util.*;

/** A received charge is durable evidence that its independent cycle history must not be silently reseeded. */
public final class WarCavalryRecovery{
    private WarCavalryRecovery(){}
    public static void verify(Collection<WarCavalry> records,Collection<WarTransmission> history,Map<UUID,WarUnit> units,WarKey charge){
        var latest=new HashMap<UUID,WarTransmission>();
        for(var t:history)if(t.received().isPresent()&&t.command().intent().kind().equals(charge))latest.merge(t.command().unitId(),t,(a,b)->a.sequence()>b.sequence()?a:b);
        var states=new HashMap<UUID,WarCavalry>();for(var state:records){
            if(states.putIfAbsent(state.unit(),state)!=null)throw new IllegalArgumentException("duplicate cavalry history");
            var unit=units.get(state.unit());var proof=latest.get(state.unit());
            if(unit==null||!unit.armyId().equals(state.army())||proof==null||!proof.command().operationId().equals(state.order())||proof.sequence()!=state.sequence()||!proof.command().armyId().equals(state.army())||!proof.command().intent().target().equals(state.objective())||!proof.members().equals(state.members()))throw new IllegalArgumentException("CAVALRY_HISTORY_SOURCE_MISMATCH");
        }
        for(var t:latest.values())if(!states.containsKey(t.command().unitId()))throw new IllegalArgumentException("RECEIVED_CHARGE_WITHOUT_CAVALRY_HISTORY");
        for(var unit:units.values())unit.currentOrder().filter(o->o.intent().kind().equals(charge)).ifPresent(order->{var state=states.get(unit.id());if(state==null||!state.order().equals(order.id())||state.sequence()!=order.sequence())throw new IllegalArgumentException("CURRENT_CHARGE_WITHOUT_MATCHING_CYCLE");});
    }
}
