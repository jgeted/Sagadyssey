package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarIntent;
import com.jgeted.sagadyssey.social.war.api.WarCohesionFrame;
import java.util.*;

/** One persistent organization score per unit; never a second individual morale/health ledger. */
public record WarCohesion(UUID unit, UUID army, long revision, long changedAt, int organization,
                           Phase phase, UUID order, long sequence, WarIntent.Position rally,
                           Set<UUID> routed, Map<UUID, Cursor> cursors, Map<ImpactKey, Impact> impacts) {
    public static final int MAX_ORGANIZATION=10_000, MAX_ENCOUNTERS=32, MAX_IMPACTS=4096;
    public enum Phase { STEADY, WAVERING, ROUTING, WITHDRAWING, REGROUPING }
    public record Cursor(int count, UUID last) {
        public Cursor { if(count<1 || count>WarCohesionFrame.MAX_EVENTS_PER_ENCOUNTER) throw new IllegalArgumentException("invalid event cursor"); Objects.requireNonNull(last); }
    }
    public record ImpactKey(UUID encounter, UUID character) {
        public ImpactKey { Objects.requireNonNull(encounter); Objects.requireNonNull(character); }
    }
    public record Impact(int severity, int originalStrength, int appliedLoss) {
        public Impact { if((severity!=1 && severity!=2) || originalStrength<1 || originalStrength>128 || appliedLoss<0 || appliedLoss>10_000) throw new IllegalArgumentException("invalid casualty impact"); }
    }
    public WarCohesion {
        Objects.requireNonNull(unit); Objects.requireNonNull(army); Objects.requireNonNull(phase); Objects.requireNonNull(order); Objects.requireNonNull(rally);
        routed=Set.copyOf(routed); cursors=Map.copyOf(cursors); impacts=Map.copyOf(impacts);
        if(revision<1 || changedAt<0 || organization<0 || organization>MAX_ORGANIZATION || sequence<1
                || routed.size()>128 || cursors.size()>MAX_ENCOUNTERS || impacts.size()>MAX_IMPACTS)
            throw new IllegalArgumentException("invalid cohesion checkpoint");
        for(var key:impacts.keySet()) if(!cursors.containsKey(key.encounter())) throw new IllegalArgumentException("orphan casualty impact");
    }
    public static boolean follows(WarCohesion old, WarCohesion next) {
        if(old.equals(next)) return true;
        if(!old.unit.equals(next.unit) || !old.army.equals(next.army) || old.revision==Long.MAX_VALUE || next.revision!=old.revision+1
                || next.changedAt<old.changedAt || next.sequence<old.sequence || next.sequence==old.sequence && !next.order.equals(old.order)
                || !next.cursors.keySet().containsAll(old.cursors.keySet()) || !next.impacts.keySet().containsAll(old.impacts.keySet())) return false;
        for(var entry:old.cursors.entrySet()) {
            var n=next.cursors.get(entry.getKey()); var p=entry.getValue();
            if(n.count()<p.count() || n.count()==p.count() && !n.equals(p)) return false;
        }
        for(var entry:old.impacts.entrySet()) {
            var n=next.impacts.get(entry.getKey()); var p=entry.getValue();
            if(n.originalStrength()!=p.originalStrength() || n.severity()<p.severity() || n.appliedLoss()<p.appliedLoss()) return false;
        }
        return true;
    }
}
