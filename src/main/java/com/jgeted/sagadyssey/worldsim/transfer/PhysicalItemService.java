package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 共享实物幂等入口：落盘意图/提交边界在先，原转移或消费在后；凭据不足就核对。 */
public final class PhysicalItemService implements AutoCloseable {
    public enum Status { APPLIED, DENIED, PENDING, UNKNOWN, UNAVAILABLE, CONFLICT }
    public record Reply(Status status, Optional<PhysicalItemOperation.Proof> proof, String reason) {}
    private final DurableSnapshotStore<UUID, PhysicalItemOperation> store;
    private final RightsStateData rights;
    private final TransferLedgerData transfers;
    /** 仅本进程在写入ISSUING之前登记的提交许可；重启后缺证据的ISSUING绝不重做。 */
    private final Set<UUID> mayExecute = new HashSet<>();
    private final Map<UUID, UUID> locked = new HashMap<>();
    private final Map<UUID, Set<UUID>> unverifiedOwners = new HashMap<>();
    private boolean changed = true;
    private long epoch = -1;
    public PhysicalItemService(Path path, RightsStateData rights, TransferLedgerData transfers) throws IOException {
        this.rights = Objects.requireNonNull(rights); this.transfers = Objects.requireNonNull(transfers);
        store = new DurableSnapshotStore<>(path, new PhysicalItemOperationCodec(), value -> value.intent().id(), PhysicalItemOperation::follows, "sagadyssey-item-operations");
        for (var record : store.recover()) if (record.phase() == PhysicalItemOperation.Phase.APPLIED) {
            for (UUID endpoint : record.intent().endpoints()) unverifiedOwners.computeIfAbsent(endpoint, ignored -> new HashSet<>()).add(record.intent().id());
        }
        refresh();
    }
    public Optional<PhysicalItemOperation> find(UUID id) { refresh(); return store.desired(id); }
    public boolean hasUnresolved(UUID container) { refresh(); return locked.containsKey(container) || unverifiedOwners.containsKey(container); }
    /** 每个实际容器回载后只核验它自己的历史凭据，不强制加载其他人的容器。 */
    public boolean verifyOwner(WitnessedItemEndpoint endpoint) {
        refresh(); if (!endpoint.available()) return false; var ids = unverifiedOwners.get(endpoint.id()); if (ids == null) return true;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next(); var record = store.desired(id).orElseThrow(); var expected = record.witness();
            var context = rights.context(endpoint.id());
            if (!expected.equals(rights.itemOperationWitness(id)) || !endpoint.witness(id).filter(expected::equals).isPresent()
                    || context == null || context.revision() < record.proof().orElseThrow().finalRevisions().get(endpoint.id())) return false;
            iterator.remove();
        }
        unverifiedOwners.remove(endpoint.id()); return true;
    }
    public Reply submit(PhysicalItemOperation.Intent proposed, Map<UUID, WitnessedItemEndpoint> endpoints, long now) {
        refresh(); if (now < 0) throw new IllegalArgumentException("invalid item operation time");
        try { return submitChecked(proposed, endpoints, now); }
        catch (RuntimeException exception) { return reply(Status.UNKNOWN, null, "ITEM_STATE_REQUIRES_REVIEW"); }
    }
    private Reply submitChecked(PhysicalItemOperation.Intent proposed, Map<UUID, WitnessedItemEndpoint> endpoints, long now) {
        var old = store.desired(proposed.id());
        if (old.isPresent() && !old.get().intent().identity().equals(proposed.identity())) return reply(Status.CONFLICT, null, "ITEM_OPERATION_ID_REUSED");
        var intent = old.map(PhysicalItemOperation::intent).orElse(proposed);
        long requested = intent.transfer().map(TransferRequest::requestedAt).orElseGet(() -> intent.consumption().orElseThrow().requestedAt());
        if (now < requested || old.isPresent() && old.get().proof().isPresent() && now < old.get().proof().get().occurredAt()) return reply(Status.UNKNOWN, null, "ITEM_OPERATION_TIME_REVERSED");
        for (UUID id : intent.endpoints()) {
            var endpoint = endpoints.get(id);
            if (endpoint == null || !id.equals(endpoint.id()) || !endpoint.available()) return reply(Status.UNAVAILABLE, null, "REAL_ITEM_OWNER_UNAVAILABLE");
            if (!verifyOwner(endpoint)) return reply(Status.UNKNOWN, null, "ITEM_OWNER_HISTORY_REQUIRES_REVIEW");
            if (locked.containsKey(id) && !locked.get(id).equals(intent.id()) && (old.isEmpty() || old.get().phase() == PhysicalItemOperation.Phase.INTENT
                    || old.get().phase() == PhysicalItemOperation.Phase.ISSUING)) return reply(Status.PENDING, null, "CONTAINER_HAS_UNRESOLVED_OPERATION");
        }
        if (old.isEmpty()) {
            // 旧物权/容器凭据存在而本日志缺失时不能当成第一次请求。
            if (rights.itemOperationWitness(intent.id()) != null || intent.endpoints().stream().anyMatch(id -> endpoints.get(id).witness(intent.id()).isPresent())) {
                return reply(Status.UNKNOWN, null, "ITEM_WITNESS_WITHOUT_INTENT");
            }
            var created = new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.INTENT, Optional.empty());
            var saved = store.persist(created); changed = true; refresh();
            return persistence(saved);
        }
        var record = old.get(); var saved = store.persist(record); refresh();
        if (saved.status() != DurableSnapshotStore.Status.APPLIED) return persistence(saved);
        if (record.phase() == PhysicalItemOperation.Phase.DENIED) return reply(Status.DENIED, record.proof().orElseThrow(), record.proof().orElseThrow().reason());
        var evidence = recover(record, endpoints);
        if (evidence.isPresent()) {
            var applied = new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.APPLIED, evidence); var confirmed = store.persist(applied); changed = true; refresh();
            return confirmed.status() == DurableSnapshotStore.Status.APPLIED ? reply(Status.APPLIED, evidence.get(), "") : persistence(confirmed);
        }
        if (record.phase() == PhysicalItemOperation.Phase.APPLIED || rights.itemOperationWitness(intent.id()) != null
                || intent.endpoints().stream().anyMatch(id -> endpoints.get(id).witness(intent.id()).isPresent())) {
            return reply(Status.UNKNOWN, null, "ITEM_DOMAIN_OR_CONTAINER_EVIDENCE_INCONSISTENT");
        }
        if (record.phase() == PhysicalItemOperation.Phase.INTENT) {
            for (UUID id : intent.endpoints()) if (!endpoints.get(id).canRecord(intent.id())) return reply(Status.UNAVAILABLE, null, "CONTAINER_EVIDENCE_FULL");
            if (!rights.canRecordItemOperation(intent.id())) return reply(Status.UNAVAILABLE, null, "SHARED_ITEM_EVIDENCE_FULL");
            mayExecute.add(intent.id()); var issuing = new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.ISSUING, Optional.empty());
            var committed = store.persist(issuing); changed = true; refresh(); return persistence(committed);
        }
        if (!mayExecute.remove(intent.id())) return reply(Status.UNKNOWN, null, "ISSUING_OUTCOME_REQUIRES_DOMAIN_EVIDENCE");
        try {
            var proof = execute(intent, endpoints, now); var outcome = new PhysicalItemOperation(intent, proof.applied() ? PhysicalItemOperation.Phase.APPLIED : PhysicalItemOperation.Phase.DENIED, Optional.of(proof));
            if (proof.applied()) {
                var witness = outcome.witness(); rights.recordItemOperation(intent.id(), witness);
                for (UUID id : intent.endpoints()) endpoints.get(id).record(intent.id(), witness);
            }
            var completed = store.persist(outcome); changed = true; refresh();
            return completed.status() == DurableSnapshotStore.Status.APPLIED ? reply(proof.applied() ? Status.APPLIED : Status.DENIED, proof, proof.reason()) : persistence(completed);
        } catch (RuntimeException exception) { return reply(Status.UNKNOWN, null, "ITEM_EFFECT_RESULT_UNCERTAIN"); }
    }
    private Optional<PhysicalItemOperation.Proof> recover(PhysicalItemOperation record, Map<UUID, WitnessedItemEndpoint> endpoints) {
        var witness = rights.itemOperationWitness(record.intent().id()); if (witness == null) return Optional.empty();
        var identity = witness.copy(); identity.remove("Proof"); if (!identity.equals(record.intent().identity())) return Optional.empty();
        var proof = PhysicalItemOperationCodec.proof(witness.getCompoundOrEmpty("Proof"));
        if (!proof.applied() || !proof.finalRevisions().keySet().equals(record.intent().endpoints())) return Optional.empty();
        if (record.proof().isPresent() && !record.proof().get().equals(proof)) return Optional.empty();
        for (UUID id : record.intent().endpoints()) {
            if (!endpoints.get(id).witness(record.intent().id()).filter(witness::equals).isPresent()
                    || rights.context(id) == null || rights.context(id).revision() < proof.finalRevisions().get(id)) return Optional.empty();
        }
        return Optional.of(proof);
    }
    private PhysicalItemOperation.Proof execute(PhysicalItemOperation.Intent intent, Map<UUID, WitnessedItemEndpoint> endpoints, long now) {
        for (UUID id : intent.endpoints()) if (!endpoints.get(id).available() || !endpoints.get(id).canRecord(intent.id())) throw new IllegalStateException("item owner changed before commit");
        if (intent.transfer().isPresent()) {
            Map<UUID, TransferEndpoint> nativeEndpoints = new HashMap<>(); intent.endpoints().forEach(id -> nativeEndpoints.put(id, new TransferEndpoint(id, endpoints.get(id).container())));
            var request = intent.transfer().orElseThrow();
            if (transfers.receipt(intent.id()) != null) throw new IllegalStateException("unmatched legacy transfer receipt");
            var result = new TransferService(rights, transfers).submit(request, nativeEndpoints, now);
            return new PhysicalItemOperation.Proof(result.successful(), result.status().name(), result.finalRevisions(), result.completedAt());
        }
        var request = intent.consumption().orElseThrow(); var context = rights.context(request.container());
        if (context == null || context.revision() != request.expectedRevision()) return new PhysicalItemOperation.Proof(false, "STALE_REVISION", Map.of(), now);
        var result = new ConsumptionService(rights).consume(request.container(), endpoints.get(request.container()).container(), request.actor(), request.item(), request.quantity(), request.authority(), now);
        return new PhysicalItemOperation.Proof(result.successful(), result.status().name(), result.successful() ? Map.of(request.container(), result.finalRevision()) : Map.of(), now);
    }
    private void refresh() {
        long current = store.epoch(); if (!changed && current == epoch) return; locked.clear();
        for (var record : store.durableValues()) if (record.phase() == PhysicalItemOperation.Phase.ISSUING) index(record);
        for (var record : store.desiredValues()) if (record.phase() == PhysicalItemOperation.Phase.ISSUING) index(record);
        epoch = current; changed = false;
    }
    private void index(PhysicalItemOperation record) {
        for (UUID id : record.intent().endpoints()) { UUID prior = locked.putIfAbsent(id, record.intent().id());
            if (prior != null && !prior.equals(record.intent().id())) throw new IllegalStateException("conflicting unresolved physical operations"); }
    }
    private Reply persistence(DurableSnapshotStore.Reply<PhysicalItemOperation> result) {
        return reply(switch (result.status()) { case APPLIED, PENDING -> Status.PENDING; case UNKNOWN -> Status.UNKNOWN; case UNAVAILABLE -> Status.UNAVAILABLE; }, null, result.reason());
    }
    private static Reply reply(Status status, PhysicalItemOperation.Proof proof, String reason) { return new Reply(status, Optional.ofNullable(proof), reason); }
    @Override public void close() { mayExecute.clear(); store.close(); }
}
