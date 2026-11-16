package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.*;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

public final class WarStaffJournal implements Journal {
    public static final String FILE_NAME="sagadyssey_war_staff.sgw";
    private final DurableSnapshotStore<UUID,WarStaffEntry> store;
    public WarStaffJournal(Path path)throws IOException{store=new DurableSnapshotStore<>(path,new WarStaffCodec(),WarStaffEntry::id,WarStaffEntry::follows,"sagadyssey-war-staff");}
    public Collection<WarStaffEntry> recover(){return store.recover();}
    public Reply persist(WarStaffEntry v){var r=store.persist(v);return new Reply(Status.valueOf(r.status().name()),r.durable(),r.reason());}
    public void close(){store.close();}
}
