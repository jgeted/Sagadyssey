package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.*;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

public final class WarCohesionJournal implements Journal {
    public static final String FILE_NAME="sagadyssey_war_cohesion.sgw";
    private final DurableSnapshotStore<UUID,WarCohesion> store;
    public WarCohesionJournal(Path path) throws IOException {
        store=new DurableSnapshotStore<>(path,new WarCohesionCodec(),WarCohesion::unit,WarCohesion::follows,"sagadyssey-war-cohesion");
    }
    public Collection<WarCohesion> recover() { return store.recover(); }
    public Written persist(WarCohesion value) {
        var reply=store.persist(value); return new Written(Status.valueOf(reply.status().name()),reply.durable(),reply.reason());
    }
    public void close() { store.close(); }
}
