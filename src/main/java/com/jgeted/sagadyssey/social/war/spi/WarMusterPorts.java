package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import java.util.*;

public final class WarMusterPorts {
    private WarMusterPorts(){}
    public enum Status { APPLIED,PENDING,UNKNOWN,UNAVAILABLE }
    public record Reply(Status status,Optional<WarMusterEntry> durable,String reason){}
    public interface Journal extends AutoCloseable {
        Collection<WarMusterEntry> recover();
        Reply persist(WarMusterEntry value);
        void close();
    }
}
