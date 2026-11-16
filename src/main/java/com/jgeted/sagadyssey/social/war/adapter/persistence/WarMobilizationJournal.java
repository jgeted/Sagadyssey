package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 战争领域的单调性适配；共用持久化机制不持有实体、容器或SavedData。 */
public final class WarMobilizationJournal implements Journal {
    @FunctionalInterface public interface Publisher { void publish(Path temporary, Path destination) throws IOException; }
    private final DurableSnapshotStore<UUID, WarMobilization> store;
    public WarMobilizationJournal(Path file, WarMobilizationCodec codec) throws IOException {
        store = new DurableSnapshotStore<>(file, adapt(codec), record -> record.request().id(), WarMobilizationJournal::follows, "sagadyssey-war-mobilization-journal");
    }
    public WarMobilizationJournal(Path file, WarMobilizationCodec codec, Publisher publisher) throws IOException {
        store = new DurableSnapshotStore<>(file, adapt(codec), record -> record.request().id(), WarMobilizationJournal::follows,
                "sagadyssey-war-mobilization-journal", publisher::publish);
    }
    private static DurableSnapshotStore.Codec<WarMobilization> adapt(WarMobilizationCodec codec) {
        return new DurableSnapshotStore.Codec<>() {
            public int maxBytes() { return codec.maxBytes(); }
            public int capacity() { return codec.maxRecords(); }
            public byte[] write(Collection<WarMobilization> records) throws IOException { return codec.write(records); }
            public Collection<WarMobilization> read(byte[] bytes) throws IOException { return codec.read(bytes); }
        };
    }
    private static boolean follows(WarMobilization previous, WarMobilization record) {
        return previous.request().equals(record.request()) && record.revision() >= previous.revision()
                && (record.revision() != previous.revision() || record.equals(previous))
                && record.receipts().entrySet().containsAll(previous.receipts().entrySet())
                && (!previous.demobilizeRequested() || record.demobilizeRequested()) && record.changedAtTick() >= previous.changedAtTick();
    }
    @Override public int protocolVersion() { return 1; }
    @Override public int capacity() { return store.capacity(); }
    @Override public Collection<WarMobilization> recover() { return store.recover(); }
    @Override public WriteReply persist(WarMobilization record) {
        var reply = store.persist(record); return new WriteReply(Status.valueOf(reply.status().name()), reply.durable(), reply.reason());
    }
    public void retry() { store.retry(); }
    @Override public void close() { store.close(); }
}
