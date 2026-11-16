package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts.*;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

public final class WarCommandPostJournal implements Journal {
    public static final String FILE_NAME="sagadyssey_war_command_posts.sgw";
    private final DurableSnapshotStore<UUID,WarCommandPost> store;
    public WarCommandPostJournal(Path path)throws IOException{store=new DurableSnapshotStore<>(path,new WarCommandPostCodec(),WarCommandPost::unit,WarCommandPost::follows,"sagadyssey-war-posts");}
    public Collection<WarCommandPost> recover(){return store.recover();}
    public Reply persist(WarCommandPost p){var r=store.persist(p);return new Reply(Status.valueOf(r.status().name()),r.durable(),r.reason());}
    public void close(){store.close();}
}
