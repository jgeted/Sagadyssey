package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.*;
import java.util.*;

/** Bounded seat and person indices. Unconfirmed appointments reserve seats but grant no powers. */
public final class WarStaffBook implements AutoCloseable {
    public static final int CAPACITY=2048;
    private final Journal journal;
    private final Map<UUID,WarStaffEntry> entries=new LinkedHashMap<>(),durable=new HashMap<>();
    private final Map<String,UUID> seats=new HashMap<>();private final Map<UUID,UUID> people=new HashMap<>();
    private final Set<UUID> pending=new LinkedHashSet<>();private String problem="";
    public WarStaffBook(Journal journal){
        this.journal=Objects.requireNonNull(journal);
        for(var v:journal.recover())if(entries.putIfAbsent(v.id(),v)!=null||entries.size()>CAPACITY)throw new IllegalArgumentException("duplicate/excessive staff evidence");
        for(var v:entries.values()){if(v instanceof Appointment a){validate(a);reserve(a);}durable.put(v.id(),v);}
    }
    public Collection<WarStaffEntry> entries(){return List.copyOf(entries.values());}
    public Optional<Establishment> establishment(UUID army){return Optional.ofNullable(entries.get(army)).filter(Establishment.class::isInstance).map(Establishment.class::cast);}
    public Optional<Appointment> appointment(UUID id){return Optional.ofNullable(entries.get(id)).filter(Appointment.class::isInstance).map(Appointment.class::cast);}
    public Optional<Appointment> forPerson(UUID person){return Optional.ofNullable(people.get(person)).flatMap(this::appointment);}
    public Optional<Appointment> captain(UUID army,UUID unit){return Optional.ofNullable(seats.get(army+"/captain/"+unit)).flatMap(this::appointment);}
    public boolean durable(WarStaffEntry v){return problem.isEmpty()&&v.equals(durable.get(v.id()));}
    public String problem(){return problem;}
    public Reply put(WarStaffEntry next){
        if(!problem.isEmpty())return failure(problem);
        var old=entries.get(next.id());
        if(old!=null&&!WarStaffEntry.follows(old,next))return failure("STAFF_IDENTITY_OR_TRANSITION_CONFLICT");
        if(old==null&&(entries.size()>=CAPACITY||next.revision()!=1||next instanceof Appointment a&&a.phase()!=Phase.PREPARING))return failure("STAFF_CAPACITY_OR_INITIAL_PHASE");
        if(next instanceof Appointment a){
            try{validate(a);}catch(IllegalArgumentException e){return failure(e.getMessage());}
            if(old==null){var e=establishment(a.terms().army()).orElseThrow();if(a.terms().establishmentRevision()!=e.revision()||a.terms().combatants()!=e.strength()
                    ||a.terms().unitCombatants()!=a.terms().unit().map(u->e.combatants().getOrDefault(u,0)).orElse(0))return failure("STAFF_REVIEW_BASIS_CHANGED");}
            if(a.reserves()&&(!Objects.equals(seats.getOrDefault(a.terms().seat(),a.id()),a.id())||!Objects.equals(people.getOrDefault(a.terms().holder(),a.id()),a.id())))return failure("STAFF_SEAT_OR_PERSON_ALREADY_RESERVED");
        }
        var reply=journal.persist(next);
        if(reply.status()==Status.APPLIED||reply.status()==Status.PENDING){
            entries.put(next.id(),next);if(next instanceof Appointment a&&a.reserves())reserve(a);pending.add(next.id());acknowledge(next,reply);
        }else problem="STAFF_JOURNAL_REQUIRES_REVIEW:"+reply.reason();
        return reply;
    }
    public void poll(int maximum){for(int i=0;i<maximum&&!pending.isEmpty();i++){var it=pending.iterator();var id=it.next();it.remove();var v=entries.get(id);var r=journal.persist(v);acknowledge(v,r);if(r.status()!=Status.APPLIED)pending.add(id);if(r.status()==Status.UNKNOWN||r.status()==Status.UNAVAILABLE)problem="STAFF_JOURNAL_REQUIRES_REVIEW:"+r.reason();}}
    private void acknowledge(WarStaffEntry v,Reply r){if(r.status()==Status.APPLIED){if(r.durable().filter(v::equals).isPresent()){durable.put(v.id(),v);pending.remove(v.id());if(v instanceof Appointment a&&!a.reserves())reserve(a);}else problem="STAFF_ACKNOWLEDGEMENT_CONTENT_MISMATCH";}}
    private void validate(Appointment a){var e=establishment(a.terms().army()).orElseThrow(()->new IllegalArgumentException("STAFF_ESTABLISHMENT_MISSING"));if(!e.owner().equals(a.terms().owner())||a.terms().establishmentRevision()>e.revision()||a.terms().unit().filter(u->!e.combatants().containsKey(u)).isPresent())throw new IllegalArgumentException("FOREIGN_OR_FUTURE_STAFF_BASIS");}
    private void reserve(Appointment a){
        if(a.reserves()){
            var seat=seats.putIfAbsent(a.terms().seat(),a.id());var person=people.putIfAbsent(a.terms().holder(),a.id());
            if(seat!=null&&!seat.equals(a.id())||person!=null&&!person.equals(a.id()))throw new IllegalArgumentException("duplicate active staff seat/person");
        }else{seats.remove(a.terms().seat(),a.id());people.remove(a.terms().holder(),a.id());}
    }
    private static Reply failure(String reason){return new Reply(Status.UNAVAILABLE,Optional.empty(),reason);}
    public void close(){journal.close();}
}
