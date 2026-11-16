package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class WarMusterJournal implements Journal {
    public static final String FILE_NAME="sagadyssey_war_muster.sgw";
    private final DurableSnapshotStore<UUID,WarMusterEntry> store;
    public WarMusterJournal(Path path)throws IOException{store=new DurableSnapshotStore<>(path,new WarMusterCodec(),WarMusterEntry::id,WarMusterEntry::follows,"sagadyssey-war-muster");}
    public Collection<WarMusterEntry> recover(){return store.recover();}
    public Reply persist(WarMusterEntry value){var r=store.persist(value);return new Reply(Status.valueOf(r.status().name()),r.durable(),r.reason());}
    public void close(){store.close();}
}
