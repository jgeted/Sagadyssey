package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import java.util.*;

public final class WarCommandPostPorts {
    private WarCommandPostPorts(){}
    public enum Status { APPLIED, PENDING, UNKNOWN, UNAVAILABLE }
    public record Reply(Status status,Optional<WarCommandPost> durable,String reason){}
    public interface Journal extends AutoCloseable {
        Collection<WarCommandPost> recover();
        Reply persist(WarCommandPost post);
        void close();
    }
}
