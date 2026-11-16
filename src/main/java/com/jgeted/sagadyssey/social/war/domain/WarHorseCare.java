package com.jgeted.sagadyssey.social.war.domain;
import java.util.*;

/** Campaign feed accounting for an original mount. No duplicate horse health or vanilla hunger. */
public record WarHorseCare(UUID id,UUID service,UUID person,UUID body,UUID owner,long fedAt,long revision){
    public static final long FEED_PERIOD=12000;
    public WarHorseCare {for(var v:List.of(id,service,person,body,owner))Objects.requireNonNull(v);if(fedAt<0||revision<1)throw new IllegalArgumentException("invalid mount care");}
    public boolean fresh(long now){return now>=fedAt&&now-fedAt<FEED_PERIOD;}
}
