package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCavalry;
import java.io.IOException;
import java.util.*;

public final class WarCavalryPorts{
    private WarCavalryPorts(){}
    public enum Status{APPLIED,PENDING,UNKNOWN,UNAVAILABLE}
    public record Written(Status status,Optional<WarCavalry> durable,String reason){}
    public record Contact(UUID targetBody,UUID targetCharacter,long requestedAt,long movingAt,double distance,double speed,double alignment){
        public Contact{Objects.requireNonNull(targetBody);Objects.requireNonNull(targetCharacter);if(requestedAt<0||movingAt<0||movingAt>requestedAt||!Double.isFinite(distance)||distance<0||!Double.isFinite(speed)||speed<0||!Double.isFinite(alignment)||alignment < -1||alignment>1)throw new IllegalArgumentException("invalid contact observation");}
    }
    public interface Journal extends AutoCloseable{Collection<WarCavalry> recover();Written persist(WarCavalry state);void close();}
    public interface JournalFactory{Journal open()throws IOException;}
    public interface Policy{
        WarCavalry plan(Optional<WarCavalry> old,WarCavalryFrame frame,boolean reformed,boolean interrupted);
        boolean permits(Contact contact,long now);
        long reformTicks();long maximumObservationGap();
    }
}
