package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 共享占用的唯一实现。接口供可信服务端适配调用，调用方必须先核验资格和原责任。 */
public final class WorkCommitmentService implements AutoCloseable {
    public enum Status { APPLIED, NOT_APPLIED, PENDING, UNKNOWN, UNAVAILABLE, BUSY, CONFLICT }
    public record Reply(Status status, Optional<WorkCommitment.Receipt> receipt, String reason) {}
    private final DurableSnapshotStore<UUID, WorkCommitment> store;
    private final Map<UUID, UUID> people = new HashMap<>(), bodies = new HashMap<>();
    private long epoch = -1;
    private boolean changed = true;
    public WorkCommitmentService(Path path) throws IOException {
        this(path, (temporary, destination) -> java.nio.file.Files.move(temporary, destination,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    }
    public WorkCommitmentService(Path path, DurableSnapshotStore.Publisher publisher) throws IOException {
        store = new DurableSnapshotStore<>(path, new WorkCommitmentCodec(), value -> value.intent().id(), WorkCommitment::follows, "sagadyssey-work-commitments", publisher);
        refresh();
    }
    public boolean holds(UUID person) { refresh(); return people.containsKey(person); }
    public Optional<WorkCommitment> holder(UUID person) { refresh(); UUID id = people.get(person); return id == null ? Optional.empty() : store.desired(id); }
    public Collection<WorkCommitment> records() { refresh(); return store.desiredValues(); }
    public Optional<WorkCommitment> find(UUID id) { refresh(); return store.desired(id); }
    public Reply lookup(WorkCommitment.Intent intent, WorkCommitment.Operation operation, UUID operationId) {
        refresh(); var value = store.desired(intent.id());
        if (value.isEmpty()) return reply(Status.NOT_APPLIED, null, "authoritative work log has no accepted operation");
        if (!value.get().intent().equals(intent)) return reply(Status.CONFLICT, null, "work intent id reused with different content");
        var receipt = value.get().receipts().get(operation);
        if (receipt == null) return reply(Status.NOT_APPLIED, null, "authoritative work sequence has not performed this step");
        if (!receipt.operationId().equals(operationId)) return reply(Status.CONFLICT, null, "work operation id conflict");
        var persisted = store.persist(value.get());
        var durable = store.durable(intent.id()).map(record -> record.receipts().get(operation)).orElse(null);
        if (receipt.equals(durable)) { refresh(); return reply(Status.APPLIED, durable, ""); }
        return reply(Status.valueOf(persisted.status().name()), null, persisted.reason());
    }
    public Reply acquire(WorkCommitment.Intent intent, UUID operationId, long now) {
        var existing = lookup(intent, WorkCommitment.Operation.CLAIM, operationId);
        if (existing.status() != Status.NOT_APPLIED) return existing;
        if (people.containsKey(intent.character()) || bodies.containsKey(intent.body())) return reply(Status.BUSY, null, "person or original body already held");
        var record = WorkCommitment.claim(intent, operationId, now); var saved = store.persist(record); changed = true; refresh();
        if (saved.status() != DurableSnapshotStore.Status.APPLIED && saved.status() != DurableSnapshotStore.Status.PENDING) return reply(Status.valueOf(saved.status().name()), null, saved.reason());
        return lookup(intent, WorkCommitment.Operation.CLAIM, operationId);
    }
    public Reply advance(WorkCommitment.Intent intent, WorkCommitment.Operation operation, UUID operationId, long now) {
        if (operation == WorkCommitment.Operation.CLAIM) return acquire(intent, operationId, now);
        var existing = lookup(intent, operation, operationId); if (existing.status() != Status.NOT_APPLIED) return existing;
        var record = store.desired(intent.id());
        if (record.isEmpty()) return reply(Status.CONFLICT, null, "work claim does not exist");
        if (!store.durable(intent.id()).filter(record.get()::equals).isPresent()) return reply(Status.PENDING, null, "previous work step is not yet durable");
        try {
            var saved = store.persist(record.get().advance(operation, operationId, now)); changed = true; refresh();
            if (saved.status() != DurableSnapshotStore.Status.APPLIED && saved.status() != DurableSnapshotStore.Status.PENDING) return reply(Status.valueOf(saved.status().name()), null, saved.reason());
            return lookup(intent, operation, operationId);
        } catch (IllegalArgumentException exception) { return reply(Status.CONFLICT, null, "invalid work transition/time"); }
    }
    public void retry() { store.retry(); }
    private void refresh() {
        long current = store.epoch(); if (!changed && epoch == current) return;
        people.clear(); bodies.clear();
        for (var record : store.durableValues()) if (record.holdsPerson()) index(record);
        for (var record : store.desiredValues()) if (record.holdsPerson()) index(record);
        epoch = current; changed = false;
    }
    private void index(WorkCommitment record) {
        var intent = record.intent(); UUID person = people.putIfAbsent(intent.character(), intent.id()), body = bodies.putIfAbsent(intent.body(), intent.id());
        if (person != null && !person.equals(intent.id()) || body != null && !body.equals(intent.id())) throw new IllegalStateException("conflicting shared work ownership; preserve log");
    }
    private static Reply reply(Status status, WorkCommitment.Receipt receipt, String reason) { return new Reply(status, Optional.ofNullable(receipt), reason); }
    @Override public void close() { store.close(); }
}
