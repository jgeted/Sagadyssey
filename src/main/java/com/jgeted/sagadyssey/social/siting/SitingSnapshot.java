package com.jgeted.sagadyssey.social.siting;

import com.jgeted.sagadyssey.social.industry.common.IndustryContract.Mode;
import com.jgeted.sagadyssey.social.industry.common.IndustryContract.Stock;
import java.util.*;

/** Explicit bounded input. Collectors must not force chunks or create identities/ledgers while querying. */
public record SitingSnapshot(String candidate, Mode mode, long observedAt, long horizonTicks,
                             boolean terrainKnown, boolean lawfulSpace, List<Household> households,
                             List<Arrival> arrivals) {
    public enum ArrivalKind { VERIFIED_PRODUCTION_FORECAST, UNDELIVERED_IMPORT }
    public record Household(UUID id, Stock foodNow, double foodPerTick, int people, int usableBeds,
                            long labourRequired, long labourAvailable, boolean knowledgeComplete) {
        public Household {
            Objects.requireNonNull(id); Objects.requireNonNull(foodNow);
            if(!Double.isFinite(foodPerTick) || foodPerTick <= 0 || people < 1 || usableBeds < 0
                    || labourRequired < 0 || labourAvailable < 0) throw new IllegalArgumentException("invalid household budget");
        }
    }
    public record Arrival(String id, UUID household, String capability, long atOffset, int foodUnits,
                          ArrivalKind kind) {
        public Arrival {
            if(id == null || id.isBlank() || capability == null || capability.isBlank()
                    || atOffset < 0 || foodUnits <= 0) throw new IllegalArgumentException("invalid future event");
            Objects.requireNonNull(household); Objects.requireNonNull(kind);
        }
    }
    public SitingSnapshot {
        if(candidate == null || candidate.isBlank() || observedAt < 0 || horizonTicks < 1)
            throw new IllegalArgumentException("invalid candidate");
        Objects.requireNonNull(mode); households=List.copyOf(households); arrivals=List.copyOf(arrivals);
        var ids=new HashSet<UUID>();
        for(var h:households) if(!ids.add(h.id())) throw new IllegalArgumentException("duplicate household");
        var events=new HashSet<String>();
        for(var a:arrivals) if(!ids.contains(a.household()) || !events.add(a.id())) throw new IllegalArgumentException("invalid event identity");
    }
}
