package com.jgeted.sagadyssey.social.war.spi;

import java.util.*;

/** The host owns encounter and opposition authority; orders and weapon adapters never manufacture it. */
public final class WarBattlePorts {
    private WarBattlePorts(){}
    public record Permit(UUID encounter,boolean immediateThreat){public Permit{Objects.requireNonNull(encounter);}}
    public interface Engagement<A>{
        /** Bounded sensing contact may enlist currently authorized original participants. */
        Optional<Permit> contact(A actor,A target,long now);
        /** Read-only revalidation, including projectile impacts. Must not create or extend encounters. */
        Optional<Permit> current(A actor,A target,long now);
    }
}
