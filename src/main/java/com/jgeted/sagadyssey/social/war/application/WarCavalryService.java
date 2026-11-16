package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.WarCavalryFrame;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import java.util.*;

/** Durable phase fence. Contact is consumed before native attack; uncertain/recovered contacts are never replayed. */
public final class WarCavalryService implements AutoCloseable{
    public record View(Status status,Optional<WarCavalry> state,String reason){}
    private record Safety(long last,long since){}
    private record Promise(UUID unit,UUID order,long wave,Contact contact){}
    private final Journal journal;private final Policy policy;
    private final Map<UUID,WarCavalry> committed=new HashMap<>(),pending=new HashMap<>();
    private final Map<UUID,String> problems=new HashMap<>();private final Map<UUID,Safety> safety=new HashMap<>();
    private final Set<UUID> interrupted=new HashSet<>();private final Map<UUID,Promise> contacts=new HashMap<>();
    private final Map<UUID,Long> observed=new HashMap<>(),reconciliation=new HashMap<>();
    public WarCavalryService(Journal journal,Policy policy){
        this.journal=Objects.requireNonNull(journal);this.policy=Objects.requireNonNull(policy);
        if(policy.reformTicks()<1||policy.maximumObservationGap()<1)throw new IllegalArgumentException("invalid cavalry timing");
        for(var v:journal.recover()){if(committed.putIfAbsent(v.unit(),v)!=null)throw new IllegalArgumentException("duplicate cavalry unit");interrupted.add(v.unit());}
    }
    public Collection<WarCavalry> records(){return List.copyOf(committed.values());}
    public View poll(UUID unit){return pending.containsKey(unit)&&!problems.containsKey(unit)?publish(pending.get(unit)):view(unit);}
    public View view(UUID unit){return new View(problems.containsKey(unit)?Status.UNKNOWN:pending.containsKey(unit)?Status.PENDING:interrupted.contains(unit)?Status.UNAVAILABLE:committed.containsKey(unit)?Status.APPLIED:Status.UNAVAILABLE,Optional.ofNullable(committed.get(unit)),problems.getOrDefault(unit,pending.containsKey(unit)?"CAVALRY_CHECKPOINT_PENDING":interrupted.contains(unit)?"CAVALRY_REOBSERVATION_REQUIRED":""));}
    public View advance(WarCavalryFrame f){
        if(problems.containsKey(f.unit()))return view(f.unit());
        var previousObservation=observed.put(f.unit(),f.now());if(previousObservation!=null&&(f.now()<previousObservation||f.now()-previousObservation>policy.maximumObservationGap()))unobserved(f.unit());
        if(pending.containsKey(f.unit()))return publish(pending.get(f.unit()));
        var a=committed.get(f.unit());boolean same=a!=null&&a.order().equals(f.order());var last=safety.get(f.unit());
        boolean continuous=same&&last!=null&&f.now()>=last.last()&&f.now()-last.last()<=policy.maximumObservationGap();
        if(!same)contacts.entrySet().removeIf(e->e.getValue().unit().equals(f.unit()));
        if(!f.assembled()||!continuous||interrupted.contains(f.unit()))safety.remove(f.unit());
        if(f.assembled())safety.put(f.unit(),new Safety(f.now(),safety.containsKey(f.unit())?safety.get(f.unit()).since():f.now()));
        boolean reformed=safety.containsKey(f.unit())&&f.now()-safety.get(f.unit()).since()>=policy.reformTicks();
        try{
            var next=policy.plan(Optional.ofNullable(a),f,reformed,interrupted.contains(f.unit()));
            if(next.equals(a))return view(f.unit());if(interrupted.contains(f.unit()))reconciliation.put(f.unit(),next.revision());return publish(next);
        }catch(RuntimeException e){return block(f.unit(),"CAVALRY_REQUIRES_REVIEW:"+e.getClass().getSimpleName());}
    }
    public View finish(UUID unit,UUID order,long wave,WarTransmission.Member member,Optional<Contact> contact,long now){
        return finish(unit,order,wave,member,contact,false,now);
    }
    public View abort(UUID unit,UUID order,long wave,WarTransmission.Member member,long now){return finish(unit,order,wave,member,Optional.empty(),true,now);}
    private View finish(UUID unit,UUID order,long wave,WarTransmission.Member member,Optional<Contact> contact,boolean directReturn,long now){
        var view=view(unit);if(view.status()!=Status.APPLIED||interrupted.contains(unit))return view;
        var a=view.state().orElseThrow();
        if(!a.order().equals(order)||a.wave()!=wave||a.phase()!=WarCavalry.Phase.RUN||!a.members().contains(member)||a.spent().contains(member.contract()))return new View(Status.UNAVAILABLE,Optional.of(a),"CONTACT_ALREADY_CONSUMED_OR_FOREIGN");
        if(contact.isPresent()&&!policy.permits(contact.get(),now))return new View(Status.UNAVAILABLE,Optional.of(a),"ACTUAL_RUN_AND_RECENT_FORWARD_MOTION_REQUIRED");
        var spent=new HashSet<>(a.spent());spent.add(member.contract());
        contact.ifPresent(c->contacts.put(member.contract(),new Promise(unit,order,wave,c)));
        var returning=new HashSet<>(a.returning());if(directReturn)returning.add(member.contract());
        try{return publish(a.change(now,a.phase(),wave,a.startedAt(),a.nextLaunchAt(),spent,returning));}catch(RuntimeException e){return block(unit,"CONTACT_REQUIRES_REVIEW:"+e.getClass().getSimpleName());}
    }
    public Optional<Contact> take(UUID unit,UUID order,long wave,WarTransmission.Member member,long now){
        var v=view(unit);if(v.status()!=Status.APPLIED||interrupted.contains(unit))return Optional.empty();
        if(v.state().filter(a->a.members().contains(member)).isEmpty())return Optional.empty();
        var p=contacts.get(member.contract());if(p==null||!p.unit().equals(unit)||!p.order().equals(order)||p.wave()!=wave)return Optional.empty();
        contacts.remove(member.contract());var a=v.state().orElseThrow();
        return a.order().equals(order)&&a.wave()==wave&&a.spent().contains(member.contract())&&now>=p.contact().requestedAt()&&now-p.contact().requestedAt()<=40?Optional.of(p.contact()):Optional.empty();
    }
    public void unobserved(UUID unit){safety.remove(unit);interrupted.add(unit);reconciliation.remove(unit);contacts.entrySet().removeIf(e->e.getValue().unit().equals(unit));}
    public View block(UUID unit,String reason){problems.put(unit,reason);unobserved(unit);return view(unit);}
    private View publish(WarCavalry next){
        pending.put(next.unit(),next);Written r;
        try{r=journal.persist(next);}catch(RuntimeException e){return block(next.unit(),"CAVALRY_JOURNAL_EXCEPTION:"+e.getClass().getSimpleName());}
        if(r.status()==Status.APPLIED){if(r.durable().filter(next::equals).isEmpty())return block(next.unit(),"CAVALRY_RECEIPT_MISMATCH");committed.put(next.unit(),next);pending.remove(next.unit());if(Objects.equals(reconciliation.get(next.unit()),next.revision())){interrupted.remove(next.unit());reconciliation.remove(next.unit());}}
        else if(r.status()!=Status.PENDING)return block(next.unit(),"CAVALRY_JOURNAL_REQUIRES_REVIEW:"+r.reason());
        return view(next.unit());
    }
    public void close(){journal.close();contacts.clear();safety.clear();}
}
