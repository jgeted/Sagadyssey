package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts.*;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class WarDispatchJournal implements Journal {
    public static final String FILE_NAME = "sagadyssey_war_dispatch.sgw";
    public static final String ARCHIVE_DIRECTORY = "sagadyssey_war_dispatch_archives";
    private static final UUID CHECKPOINT = new UUID(0, 0x574152484f54L);
    private static final int HOT_LIMIT = 4096;
    private static final int BYTE_LIMIT = 16 * 1024 * 1024;
    private record Hot(Map<UUID, WarTransmission> records, Optional<WarDispatchArchive.Ref> head) {
        Hot { records = Map.copyOf(records); }
        static Hot empty() { return new Hot(Map.of(), Optional.empty()); }
        static Hot of(Collection<WarTransmission> rows, Optional<WarDispatchArchive.Ref> head) {
            var index = new HashMap<UUID, WarTransmission>();
            for (var row : rows)
                if (index.putIfAbsent(row.command().operationId(), row) != null) throw new IllegalArgumentException("duplicate dispatch ID");
            return new Hot(index,head);
        }
        static boolean follows(Hot prior, Hot next) {
            if (prior.equals(next)) return true;
            if (prior.head.equals(next.head)) {
                if (next.records.size() < prior.records.size()) return false;
                for (var entry : prior.records.entrySet()) {
                    var row = next.records.get(entry.getKey());
                    if (row == null || !WarTransmission.follows(entry.getValue(),row)) return false;
                }
                return true;
            }
            if (next.head.isEmpty()) return false;
            var ref = next.head.orElseThrow();
            if (ref.sequence() != prior.head.map(WarDispatchArchive.Ref::sequence).orElse(0L) + 1
                    || ref.total() != prior.head.map(WarDispatchArchive.Ref::total).orElse(0L) + ref.count()
                    || prior.records.size() - next.records.size() != ref.count()) return false;
            return next.records.entrySet().stream().allMatch(row -> row.getValue().equals(prior.records.get(row.getKey())))
                    && prior.records.entrySet().stream().allMatch(row -> next.records.containsKey(row.getKey()) || row.getValue().received().isPresent());
        }
    }
    private record Pruning(WarDispatchArchive.Ref head, Set<UUID> ids) {}

    private final DurableSnapshotStore<UUID, Hot> store;
    private final WarDispatchArchive archives;
    private CompletableFuture<WarDispatchArchive.Ref> publishing;
    private List<WarTransmission> inArchive = List.of();
    private Pruning pruning;
    private String archiveProblem = "";

    public WarDispatchJournal(Path path) throws IOException {
        var directory = path.resolveSibling(ARCHIVE_DIRECTORY);
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS) && Files.exists(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("dispatch archives exist without a hot checkpoint");
        store = new DurableSnapshotStore<>(path, new HistoryCodec(), ignored -> CHECKPOINT, Hot::follows, "sagadyssey-war-dispatch");
        archives = new WarDispatchArchive(directory);
        try {
            var head = hot().head();
            if (head.isPresent()) archives.verify(head.orElseThrow());
        } catch (IOException | RuntimeException damaged) {
            close();
            throw damaged;
        }
    }
    private Hot hot() { return store.desired(CHECKPOINT).orElseGet(Hot::empty); }
    public int capacity() { return HOT_LIMIT; }
    public Collection<WarTransmission> recover() { store.recover(); return List.copyOf(hot().records().values()); }

    public Written persist(WarTransmission value) {
        var id = value.command().operationId();
        var desired = hot();
        var old = desired.records().get(id);
        if (old == null && (desired.records().size() >= HOT_LIMIT || pruning != null && pruning.ids().contains(id)))
            return new Written(Status.UNAVAILABLE,Optional.empty(),"dispatch history is full or retiring");
        if (old != null && !WarTransmission.follows(old,value))
            return new Written(Status.UNKNOWN,Optional.empty(),"conflicting dispatch identity");
        if (!value.equals(old)) {
            var rows = new HashMap<>(desired.records());
            rows.put(id,value);
            desired = new Hot(rows,desired.head());
        }
        var saved = store.persist(desired);
        var durable = store.durable(CHECKPOINT).map(h -> h.records().get(id));
        if (durable.filter(value::equals).isPresent()) return new Written(Status.APPLIED,durable,"");
        return new Written(Status.valueOf(saved.status().name()),durable,saved.reason());
    }

    @Override public boolean wantsArchiveCandidates() { return archiveProblem.isEmpty() && publishing == null && pruning == null; }
    @Override public String archiveProblem() { return archiveProblem; }
    @Override public long archivedCount() { return store.durable(CHECKPOINT).flatMap(Hot::head).map(WarDispatchArchive.Ref::total).orElse(0L); }

    @Override public Set<UUID> maintain(List<WarTransmission> candidates) {
        if (!archiveProblem.isEmpty()) return Set.of();
        try {
            if (pruning != null) {
                var ack = store.persist(hot());
                if (ack.status() == DurableSnapshotStore.Status.UNKNOWN || ack.status() == DurableSnapshotStore.Status.UNAVAILABLE)
                    throw new IllegalStateException(ack.reason());
                if (store.durable(CHECKPOINT).flatMap(Hot::head).filter(pruning.head()::equals).isEmpty()) return Set.of();
                var removed = pruning.ids();
                pruning = null;
                return removed;
            }
            if (publishing == null && !candidates.isEmpty()) {
                var durable = store.durable(CHECKPOINT).orElseGet(Hot::empty);
                inArchive = candidates.stream().filter(row -> row.received().isPresent()
                        && row.equals(durable.records().get(row.command().operationId()))).limit(WarDispatchArchive.PAGE_LIMIT).toList();
                if (!inArchive.isEmpty()) publishing = archives.write(new WarDispatchArchive.Page(hot().head(),inArchive));
            } else if (publishing != null && publishing.isDone()) {
                var head = publishing.join();
                var current = hot();
                var rows = new HashMap<>(current.records());
                var ids = new HashSet<UUID>();
                for (var row : inArchive) {
                    var id = row.command().operationId();
                    if (!rows.remove(id,row)) throw new IllegalStateException("dispatch changed during archive");
                    ids.add(id);
                }
                var saved = store.persist(new Hot(rows,Optional.of(head)));
                if (saved.status() == DurableSnapshotStore.Status.UNKNOWN || saved.status() == DurableSnapshotStore.Status.UNAVAILABLE)
                    throw new IllegalStateException(saved.reason());
                pruning = new Pruning(head,Set.copyOf(ids));
                publishing = null;
                inArchive = List.of();
            }
        } catch (RuntimeException unavailable) {
            archiveProblem = "DISPATCH_ARCHIVE_REVIEW:" + unavailable.getClass().getSimpleName();
        }
        return Set.of();
    }

    public void close() { store.close(); archives.close(); }

    private static final class HistoryCodec implements DurableSnapshotStore.Codec<Hot> {
        private final WarDispatchCodec rows = new WarDispatchCodec();
        private final CompactedCodec compacted = new CompactedCodec();

        public int capacity() { return 1; }
        public int maxBytes() { return BYTE_LIMIT; }
        public byte[] write(Collection<Hot> checkpoints) throws IOException {
            var hot = checkpoints.iterator().next();
            return hot.head().isEmpty() ? rows.write(hot.records().values()) : compacted.write(checkpoints);
        }
        public Collection<Hot> read(byte[] bytes) throws IOException {
            try { return compacted.read(bytes); }
            catch (IOException newFormat) {
                try { return List.of(Hot.of(rows.read(bytes),Optional.empty())); }
                catch (IOException legacy) { newFormat.addSuppressed(legacy); throw newFormat; }
            }
        }
    }

    private static final class CompactedCodec extends NbtSnapshotCodec<Hot> {
        private final WarDispatchCodec rows = new WarDispatchCodec();
        CompactedCodec() { super("sagadyssey:war_dispatch/v2",BYTE_LIMIT,1); }
        @Override protected UUID identity(Hot hot) { return CHECKPOINT; }
        @Override protected CompoundTag encode(Hot hot) {
            var tag = new CompoundTag();
            tag.put("Archive",WarDispatchArchive.encodeRef(hot.head().orElseThrow()));
            try { tag.putByteArray("Hot",rows.write(hot.records().values())); }
            catch (IOException failure) { throw new IllegalArgumentException("invalid hot dispatch contents",failure); }
            return tag;
        }
        @Override protected Hot decode(CompoundTag tag) {
            WarHazardCodec.fields(tag,"Archive,Hot");
            var ref = WarDispatchArchive.decodeRef(WarHazardCodec.compound(tag,"Archive"));
            try { return Hot.of(rows.read(tag.getByteArray("Hot").orElseThrow(IllegalArgumentException::new)),Optional.of(ref)); }
            catch (IOException failure) { throw new IllegalArgumentException("invalid hot dispatch contents",failure); }
        }
    }
}
