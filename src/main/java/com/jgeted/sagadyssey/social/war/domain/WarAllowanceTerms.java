package com.jgeted.sagadyssey.social.war.domain;
import java.math.BigInteger;
import java.util.*;

/** An optional, explicit office stipend, never a replacement for the original service wage. */
public record WarAllowanceTerms(UUID id,UUID army,UUID owner,UUID person,UUID body,UUID service,long startsAt,long endsAt,int daily){
    public WarAllowanceTerms {for(var v:List.of(id,army,owner,person,body,service))Objects.requireNonNull(v);if(startsAt<0||endsAt<=startsAt||endsAt-startsAt>30L*24000||daily<1||daily>16)throw new IllegalArgumentException("invalid office allowance");}
    public BigInteger due(long now,OptionalLong stoppedAt){
        if(now<startsAt||stoppedAt.isPresent()&&(stoppedAt.getAsLong()<startsAt||stoppedAt.getAsLong()>endsAt))throw new IllegalArgumentException("invalid allowance time");
        long end=Math.min(now,stoppedAt.orElse(endsAt));var n=BigInteger.valueOf(Math.max(0,end-startsAt)).multiply(BigInteger.valueOf(daily));
        return stoppedAt.isPresent()||now>=endsAt?n.add(BigInteger.valueOf(23999)).divide(BigInteger.valueOf(24000)):n.divide(BigInteger.valueOf(24000));
    }
}
