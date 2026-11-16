package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import java.util.*;

public final class WarStaffPorts {
    private WarStaffPorts(){}
    public enum Status { APPLIED,PENDING,UNKNOWN,UNAVAILABLE }
    public record Reply(Status status,Optional<WarStaffEntry> durable,String reason){}
    public interface Journal extends AutoCloseable {
        Collection<WarStaffEntry> recover();
        Reply persist(WarStaffEntry value);
        void close();
    }
}
