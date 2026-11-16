package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** Bounded observations of this unit only. Morale is read from its existing authority. */
public record WarCohesionFrame(UUID unit, UUID army, UUID order, long sequence, OrderKind kind,
                                WarIntent.Position target, WarIntent.Position origin, long now,
                                List<Member> members, List<Events> encounters, Leadership leadership) {
    public static final int MAX_EVENTS_PER_ENCOUNTER = 1024;

    public enum OrderKind { COMBAT, WITHDRAW, RALLY }
    public enum Shock { NONE, WOUNDED, KILLED }
    public record Leadership(int formationLossDiscount, int rallyRecoveryBonus, long effectiveFrom) {
        public static final Leadership NONE = new Leadership(0, 0, 0);
        public Leadership {
            if (formationLossDiscount < 0 || formationLossDiscount > 16 || rallyRecoveryBonus < 0 || rallyRecoveryBonus > 120 || effectiveFrom < 0)
                throw new IllegalArgumentException("invalid leadership influence");
        }
    }
    public WarCohesionFrame(UUID unit, UUID army, UUID order, long sequence, OrderKind kind,
                            WarIntent.Position target, WarIntent.Position origin, long now, List<Member> members, List<Events> encounters) {
        this(unit, army, order, sequence, kind, target, origin, now, members, encounters, Leadership.NONE);
    }
    public record Member(UUID contract, UUID character, UUID body, boolean available,
                         int morale, boolean atRally, boolean pressured) {
        public Member {
            Objects.requireNonNull(contract); Objects.requireNonNull(character); Objects.requireNonNull(body);
            if (morale < 0 || morale > 100) throw new IllegalArgumentException("invalid authoritative morale");
        }
    }
    public record Event(UUID id, UUID character, Shock shock, long occurredAt) {
        public Event {
            Objects.requireNonNull(id); Objects.requireNonNull(character); Objects.requireNonNull(shock);
            if (occurredAt < 0) throw new IllegalArgumentException("invalid shock time");
        }
    }
    public record Events(UUID encounter, List<Event> events) {
        public Events {
            Objects.requireNonNull(encounter); events=List.copyOf(events);
            if (events.size()>MAX_EVENTS_PER_ENCOUNTER || events.stream().map(Event::id).distinct().count()!=events.size())
                throw new IllegalArgumentException("invalid encounter observation");
        }
    }
    public WarCohesionFrame {
        Objects.requireNonNull(unit); Objects.requireNonNull(army); Objects.requireNonNull(order); Objects.requireNonNull(kind);
        Objects.requireNonNull(target); Objects.requireNonNull(origin); Objects.requireNonNull(leadership); members=List.copyOf(members); encounters=List.copyOf(encounters);
        if (sequence<1 || now<leadership.effectiveFrom() || members.isEmpty() || members.size()>128 || encounters.size()>32
                || members.stream().map(Member::contract).distinct().count()!=members.size()
                || members.stream().map(Member::character).distinct().count()!=members.size()
                || members.stream().map(Member::body).distinct().count()!=members.size()
                || encounters.stream().map(Events::encounter).distinct().count()!=encounters.size()
                || !target.dimension().equals(origin.dimension())
                || encounters.stream().flatMap(e->e.events().stream()).anyMatch(e->e.occurredAt()>now))
            throw new IllegalArgumentException("invalid unit observation");
    }
    public boolean safeAtRally() {
        return members.stream().allMatch(Member::available) && members.stream().noneMatch(Member::pressured)
                && members.stream().filter(Member::atRally).count()*5 >= members.size()*4L;
    }
}
