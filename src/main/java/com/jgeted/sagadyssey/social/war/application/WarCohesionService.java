package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.*;
import java.util.*;

/** A per-unit durable publication fence. Pending/unknown tactical state never authorizes attacks. */
public final class WarCohesionService implements AutoCloseable {
    public record View(Status status, Optional<WarCohesion> state, String reason) {}
    private record Safety(long observedAt,long since,long nextRecovery) {}
    private final Journal journal;
    private final Policy policy;
    private final Map<UUID,WarCohesion> committed=new HashMap<>(), pending=new HashMap<>();
    private final Map<UUID,Safety> safety=new HashMap<>();
    private final Map<UUID,View> problems=new HashMap<>();
    public WarCohesionService(Journal journal,Policy policy) {
        this.journal=Objects.requireNonNull(journal); this.policy=Objects.requireNonNull(policy);
        if(policy.recoveryTicks()<1 || policy.maximumObservationGap()<1) throw new IllegalArgumentException("invalid tactical observation timing");
        for(var state:journal.recover()) if(committed.putIfAbsent(state.unit(),state)!=null) throw new IllegalArgumentException("duplicate tactical unit");
    }
    public View view(UUID unit) {
        if(problems.containsKey(unit)) return problems.get(unit);
        return new View(pending.containsKey(unit)?Status.PENDING:committed.containsKey(unit)?Status.APPLIED:Status.UNAVAILABLE,Optional.ofNullable(committed.get(unit)),
                pending.containsKey(unit)?"TACTICAL_CHECKPOINT_PENDING":committed.containsKey(unit)?"":"COHESION_OBSERVATION_REQUIRED");
    }
    /** Preserve the published control for this member while a fresh compatible observation is saved. */
    public View controlView(UUID unit, UUID contract, long now) {
        var status = view(unit);
        if (status.status() != Status.PENDING || status.state().isEmpty()) return status;
        var saved = status.state().orElseThrow();
        var change = pending.get(unit);
        if (now < change.changedAt() || now - change.changedAt() > policy.maximumObservationGap()
                || !saved.order().equals(change.order()) || saved.sequence() != change.sequence()
                || saved.phase() != change.phase() || !saved.rally().equals(change.rally())
                || saved.routed().contains(contract) != change.routed().contains(contract)) return status;
        return new View(Status.APPLIED, status.state(), "");
    }
    public View advance(WarCohesionFrame frame) {
        if(problems.containsKey(frame.unit())) return problems.get(frame.unit());
        var candidate=pending.get(frame.unit());
        if(candidate==null) {
            var old=committed.get(frame.unit()); var last=safety.get(frame.unit());
            boolean safe=frame.safeAtRally() && (old==null || old.order().equals(frame.order()));
            if(!safe) safety.remove(frame.unit());
            else if(last==null || frame.now()<last.observedAt() || frame.now()-last.observedAt()>policy.maximumObservationGap())
                safety.put(frame.unit(),new Safety(frame.now(),frame.now(),add(frame.now(),policy.recoveryTicks())));
            else safety.put(frame.unit(),new Safety(frame.now(),last.since(),last.nextRecovery()));
            var clock=safety.get(frame.unit()); boolean earned=clock!=null && frame.now()>=clock.nextRecovery();
            try { candidate=policy.plan(Optional.ofNullable(old),frame,earned); }
            catch(RuntimeException conflict) { return block(frame.unit(),"COHESION_REQUIRES_REVIEW:"+conflict.getClass().getSimpleName()); }
            if(earned) safety.put(frame.unit(),new Safety(frame.now(),clock.since(),add(frame.now(),policy.recoveryTicks())));
            if(candidate.equals(old)) return view(frame.unit());
            pending.put(frame.unit(),candidate);
        }
        Written result=journal.persist(candidate);
        if(result.status()==Status.APPLIED) {
            if(result.durable().filter(candidate::equals).isEmpty()) return block(frame.unit(),"TACTICAL_JOURNAL_RECEIPT_MISMATCH");
            committed.put(frame.unit(),candidate); pending.remove(frame.unit());
        } else if(result.status()!=Status.PENDING) return block(frame.unit(),"TACTICAL_JOURNAL_REQUIRES_REVIEW:"+result.reason());
        return view(frame.unit());
    }
    public View block(UUID unit,String reason) {
        var view=new View(Status.UNKNOWN,Optional.ofNullable(committed.get(unit)),reason); problems.put(unit,view); safety.remove(unit); return view;
    }
    public void unobserved(UUID unit) { safety.remove(unit); }
    private static long add(long now,long ticks) { return now>Long.MAX_VALUE-ticks?Long.MAX_VALUE:now+ticks; }
    @Override public void close() { journal.close(); safety.clear(); }
}
