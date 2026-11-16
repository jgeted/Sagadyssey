package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarCavalry;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class WarCavalryJournal implements Journal{
    public static final String FILE_NAME="sagadyssey_war_cavalry.sgw";
    private final DurableSnapshotStore<UUID,WarCavalry> store;
    public WarCavalryJournal(Path path)throws IOException{store=new DurableSnapshotStore<>(path,new WarCavalryCodec(),WarCavalry::unit,WarCavalry::follows,"sagadyssey-war-cavalry");}
    public Collection<WarCavalry> recover(){return store.recover();}
    public Written persist(WarCavalry v){var r=store.persist(v);return new Written(Status.valueOf(r.status().name()),r.durable(),r.reason());}
    public void close(){store.close();}
}
