package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** An army's dated observation, never an authoritative copy of the world. */
public record WarHazardObservation(UUID id,UUID army,UUID observer,UUID body,WarIntent.Position position,
                                   WarKey kind,String state,long observedAt,Source source){
    public enum Source { SURVEY,BUILT }
    public WarHazardObservation{
        for(var v:List.of(id,army,observer,body,position,kind,source))Objects.requireNonNull(v);
        if(observedAt<0||state==null||state.isBlank()||state.length()>1024||Math.abs((long)position.x())>30_000_000||Math.abs((long)position.z())>30_000_000||position.y()< -2048||position.y()>2047
                ||!Set.of(WarKey.parse("sagadyssey:war/hazard/spike"),WarKey.parse("sagadyssey:war/hazard/web")).contains(kind))
            throw new IllegalArgumentException("invalid hazard observation");
    }
}
