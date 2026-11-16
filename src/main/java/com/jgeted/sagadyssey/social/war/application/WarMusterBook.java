package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.*;
import java.util.*;

/** Bounded name/owner/offer indices. Pending edits reserve a person immediately but never grant command authority. */
public final class WarMusterBook implements AutoCloseable {
    public static final int MAX_ARMIES=32,MAX_UNITS=128,MAX_OFFERS=8_192;
    private final Journal journal;
    private final Map<UUID,WarMusterEntry> entries=new LinkedHashMap<>(),durable=new HashMap<>();
    private final Map<UUID,Offer> latest=new HashMap<>();
    private final Map<UUID,Integer> armiesByOwner=new HashMap<>(),unitsByArmy=new HashMap<>(),pendingCombatantsByUnit=new HashMap<>();
    private final Set<UUID> pending=new LinkedHashSet<>();
    private int armies,units,offers;
    private String problem="";
    public WarMusterBook(Journal journal){
        this.journal=Objects.requireNonNull(journal);
        for(var e:journal.recover())if(entries.putIfAbsent(e.id(),e)!=null)throw new IllegalArgumentException("duplicate muster identity");
        for(var e:entries.values()){validateReferences(e);count(e);durable.put(e.id(),e);if(e instanceof Offer o){var old=latest.get(o.request().characterId());
            updateCombatantSeats(null,o);
            if(old!=null&&old.unresolved()&&o.unresolved())throw new IllegalArgumentException("multiple unresolved offers for one person");
            if(old==null||old.changedAt()<o.changedAt()||old.changedAt()==o.changedAt()&&old.revision()<o.revision())latest.put(o.request().characterId(),o);}}
        if(armies>MAX_ARMIES||units>MAX_UNITS||offers>MAX_OFFERS)throw new IllegalArgumentException("muster capacity exceeded");
        if(armiesByOwner.values().stream().anyMatch(n->n>4))throw new IllegalArgumentException("owner army capacity exceeded");
        if(unitsByArmy.values().stream().anyMatch(n->n>32))throw new IllegalArgumentException("army unit capacity exceeded");
        for(var e:entries.values()){
            if(e instanceof Offer o&&o.unresolved()&&!latest.get(o.request().characterId()).id().equals(o.id()))throw new IllegalArgumentException("unresolved offer hidden by later history");
        }
    }
    public Optional<WarMusterEntry> find(UUID id){return Optional.ofNullable(entries.get(id));}
    public Optional<Army> army(UUID id){return find(id).filter(Army.class::isInstance).map(Army.class::cast);}
    public Optional<Unit> unit(UUID id){return find(id).filter(Unit.class::isInstance).map(Unit.class::cast);}
    public Optional<Offer> offer(UUID id){return find(id).filter(Offer.class::isInstance).map(Offer.class::cast);}
    public Optional<Offer> latest(UUID person){return Optional.ofNullable(latest.get(person));}
    public Collection<WarMusterEntry> entries(){return List.copyOf(entries.values());}
    public boolean isEmpty(){return entries.isEmpty();}
    public String offerAdmission(WarMilitiaRequest request,long now){
        if(offers>=MAX_OFFERS)return "MUSTER_HISTORY_CAPACITY";
        var prior=latest.get(request.characterId());
        if(prior!=null&&prior.reserves(now))return "PERSON_ALREADY_RESERVED";
        if(request.role()==WarMilitiaRequest.Role.COMBATANT&&pendingCombatantsByUnit.getOrDefault(request.unitId(),0)>=WarTransmission.MAX_MEMBERS)
            return "UNIT_COMBATANT_CAPACITY";
        return "";
    }
    public String problem(){return problem;}
    public boolean durable(WarMusterEntry value){return value.equals(durable.get(value.id()));}
    public boolean owns(UUID actor,UUID army){return problem.isEmpty()&&this.army(army).filter(a->durable(a)&&a.owner().equals(actor)).isPresent();}
    public Reply put(WarMusterEntry value,long now){
        var old=entries.get(value.id());
        if(old!=null&&!WarMusterEntry.follows(old,value))return failure("MUSTER_IDENTITY_OR_TRANSITION_CONFLICT");
        if(old==null){
            try{validateReferences(value);}catch(IllegalArgumentException invalid){return failure(invalid.getMessage());}
            if(value instanceof Army a && (armies>=MAX_ARMIES||armiesByOwner.getOrDefault(a.owner(),0)>=4))return failure("ARMY_CAPACITY");
            if(value instanceof Unit u && (units>=MAX_UNITS||u.recorded()||unitsByArmy.getOrDefault(u.army(),0)>=32))return failure("UNIT_CAPACITY_OR_UNWITNESSED_REGISTRATION");
            if(value instanceof Offer o){
                String reason=offerAdmission(o.request(),now);
                if(!reason.isEmpty())return failure(reason);
                if(o.stage()!=Stage.PROPOSED)return failure("INITIAL_OFFER_REQUIRED");
            }
        }
        var reply=journal.persist(value);
        if(reply.status()==Status.UNKNOWN||reply.status()==Status.UNAVAILABLE)problem="MUSTER_JOURNAL_REQUIRES_REVIEW:"+reply.reason();
        if(reply.status()==Status.APPLIED||reply.status()==Status.PENDING){
            if(old==null)count(value);
            entries.put(value.id(),value);pending.add(value.id());
            if(value instanceof Offer o){updateCombatantSeats(old instanceof Offer previous?previous:null,o);latest.put(o.request().characterId(),o);}
            acknowledge(value,reply);
        }
        return reply;
    }
    public void poll(int maximum){
        for(int i=0;i<maximum&&!pending.isEmpty();i++){var it=pending.iterator();var id=it.next();it.remove();var value=entries.get(id);var r=journal.persist(value);
            acknowledge(value,r);if(r.status()!=Status.APPLIED)pending.add(id);if(r.status()==Status.UNKNOWN||r.status()==Status.UNAVAILABLE)problem="MUSTER_JOURNAL_REQUIRES_REVIEW:"+r.reason();}
    }
    private void acknowledge(WarMusterEntry value,Reply reply){if(reply.status()==Status.APPLIED){if(reply.durable().filter(value::equals).isPresent()){durable.put(value.id(),value);pending.remove(value.id());}else problem="MUSTER_ACKNOWLEDGED_DIFFERENT_CONTENT_REQUIRES_REVIEW";}}
    private void validateReferences(WarMusterEntry e){
        if(e instanceof Unit u && !(entries.get(u.army()) instanceof Army))throw new IllegalArgumentException("ORPHAN_MUSTER_UNIT");
        if(e instanceof Offer o){var r=o.request();
            if(!(entries.get(r.armyId()) instanceof Army a)||!a.owner().equals(r.actorId())||!(entries.get(r.unitId()) instanceof Unit u)||!u.recorded()
                    ||!u.army().equals(a.id())||!u.profile().equals(r.loadout())||u.definitionVersion()!=r.definitionVersion())throw new IllegalArgumentException("ORPHAN_OR_FOREIGN_MUSTER_OFFER");}
    }
    private void count(WarMusterEntry e){
        if(e instanceof Army a){armies++;armiesByOwner.merge(a.owner(),1,Integer::sum);}
        else if(e instanceof Unit u){units++;unitsByArmy.merge(u.army(),1,Integer::sum);}
        else offers++;
    }
    private void updateCombatantSeats(Offer before,Offer after){
        if(after.request().role()!=WarMilitiaRequest.Role.COMBATANT)return;
        boolean held=before!=null&&before.unresolved(),holds=after.unresolved();
        if(held!=holds)pendingCombatantsByUnit.compute(after.request().unitId(),(id,value)->{
            int seats=(value==null?0:value)+(holds?1:-1);
            return seats==0?null:seats;
        });
    }
    private static Reply failure(String reason){return new Reply(Status.UNKNOWN,Optional.empty(),reason);}
    public void close(){journal.close();}
}
