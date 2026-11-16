package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts;
import java.util.*;
import java.util.function.Predicate;

/** 传递中不等于收到。每个接收结果先落盘，才可由实体执行器查询为有效指令。 */
public final class WarDispatchService {
    public static final int ARCHIVE_HIGH_WATER = 128;
    private static final long RECENT_RECEPTION_TICKS = 2400;
    private static final long IN_FLIGHT_SAFETY_TICKS = 200;
    public enum Outcome { ACCEPTED, REPLAYED, WAITING, RECEIVED, SUPERSEDED, REVIEW, DENIED, UNAVAILABLE, CONFLICT, FULL }
    public record Result(Outcome outcome, Optional<WarTransmission> record, String reason) {}

    /** Dispatch uses the same authorization and order state as the organization use case. */
    public interface Organization {
        WarCommandService.Permission authorize(UUID actor, UUID army);
        WarResult submit(WarCommand.IssueOrder command, long now);
        WarResult deliver(UUID unit, long generation, UUID order, long now);
        Optional<WarUnit> unit(UUID id);
    }

    private final Thread owner = Thread.currentThread();
    private final Organization organization;
    private final WarDispatchPorts.Journal journal;
    private final Map<UUID, WarTransmission> records = new LinkedHashMap<>();
    private final Map<UUID, WarTransmission> latest = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> memberSlots = new HashMap<>();
    private long coldRecords;
    public WarDispatchService(Organization organization, WarDispatchPorts.Journal journal) {
        this.organization = Objects.requireNonNull(organization); this.journal = Objects.requireNonNull(journal);
        for (var record : journal.recover()) {
            if (records.size() >= journal.capacity() || records.putIfAbsent(record.command().operationId(), record) != null) throw new IllegalArgumentException("invalid recovered transmissions");
            if (record.received().isPresent()) publish(record);
        }
        coldRecords = journal.archivedCount();
    }
    public Collection<WarTransmission> records() { check(); return List.copyOf(records.values()); }
    public Optional<WarTransmission> find(UUID order) { check(); return Optional.ofNullable(records.get(order)); }
    /** Confirm the exact currently tracked transmission before another journal publishes a receipt. */
    public boolean checkpointed(WarTransmission value) {
        check();
        if (!value.equals(records.get(value.command().operationId()))) return false;
        var saved = journal.persist(value);
        return saved.status() == WarDispatchPorts.Status.APPLIED && saved.durable().filter(value::equals).isPresent();
    }
    public Result begin(WarCommand.IssueOrder command, WarTransmission.Route route, List<WarTransmission.Member> members, long now) {
        check(); var permission = organization.authorize(command.actorId(), command.armyId());
        if (permission != WarCommandService.Permission.ALLOWED) return result(permission == WarCommandService.Permission.DENIED ? Outcome.DENIED : Outcome.UNAVAILABLE, null, "ARMY_AUTHORITY_REQUIRED");
        var old = records.get(command.operationId());
        if (old != null) return result(old.command().equals(command) ? Outcome.REPLAYED : Outcome.CONFLICT, old, "");
        if (records.size() >= journal.capacity()) return result(Outcome.FULL, null, "TRANSMISSION_HISTORY_FULL");
        // Validate route/roster before accepting any organization mutation.
        try { new WarTransmission(command, 1, route, members, Optional.empty()); }
        catch (IllegalArgumentException exception) { return result(Outcome.UNAVAILABLE, null, "INVALID_ROUTE_OR_ROSTER"); }
        if (now != route.sentAt()) return result(Outcome.UNAVAILABLE, null, "TRANSMISSION_TIME_MISMATCH");
        var accepted = organization.submit(command, now);
        if (accepted.status() == WarResult.Status.REPLAYED) return result(Outcome.REVIEW, null, "ORIGINAL_DISPATCH_ALREADY_ACCEPTED_WITHOUT_HOT_RECEIPT");
        if (accepted.status() != WarResult.Status.APPLIED) return result(Outcome.UNAVAILABLE, null, "CORE_REJECTED:" + accepted.status());
        var value = new WarTransmission(command, accepted.receipt().orElseThrow().orderSequence(), route, members, Optional.empty()); records.put(command.operationId(), value);
        return result(Outcome.ACCEPTED, value, "accepted intent; reception still requires durable transit and delivery");
    }
    public Result advance(UUID id, long now) {
        check(); var value = records.get(id); if (value == null) return result(Outcome.UNAVAILABLE, null, "TRANSMISSION_NOT_FOUND");
        try { return advance(value, now); }
        catch (RuntimeException exception) { return result(Outcome.REVIEW, records.get(id), "TRANSMISSION_RESULT_REQUIRES_REVIEW:" + exception.getClass().getSimpleName()); }
    }
    private Result advance(WarTransmission value, long now) {
        UUID id = value.command().operationId();
        if (now < value.route().sentAt()) return result(Outcome.REVIEW, value, "TRANSMISSION_TIME_REVERSED");
        var written = journal.persist(value);
        if (written.status() != WarDispatchPorts.Status.APPLIED) return result(written.status() == WarDispatchPorts.Status.PENDING ? Outcome.WAITING : Outcome.REVIEW, value, written.reason());
        if (!written.durable().filter(value::equals).isPresent()) return result(Outcome.REVIEW, value, "TRANSMISSION_ACK_CONTENT_MISMATCH");
        var unit = organization.unit(value.command().unitId());
        if (unit.isEmpty() || !unit.get().armyId().equals(value.command().armyId())) return result(Outcome.REVIEW, value, "TRANSMISSION_CORE_UNIT_MISSING");
        var current = unit.get().currentOrder();
        if (current.isPresent() && current.get().sequence() > value.sequence()) return result(Outcome.SUPERSEDED, value, "newer order already received");
        if (value.received().isPresent()) {
            if (!current.equals(value.received())) return result(Outcome.REVIEW, value, "RECEIVED_ORDER_CORE_CHECKPOINT_MISMATCH");
            publish(value); return result(Outcome.RECEIVED, value, "");
        }
        if (organization.authorize(value.command().actorId(), value.command().armyId()) != WarCommandService.Permission.ALLOWED) return result(Outcome.WAITING, value, "ISSUER_AUTHORITY_NO_LONGER_AVAILABLE");
        if (now < value.route().dueAt()) return result(Outcome.WAITING, value, "IN_TRANSIT");
        var receipt = organization.deliver(value.command().unitId(), value.command().generation(), id, now);
        if (receipt.status() == WarResult.Status.STALE_ORDER) return result(Outcome.SUPERSEDED, value, "old order cannot replace a newer delivery");
        if (receipt.status() != WarResult.Status.APPLIED && receipt.status() != WarResult.Status.REPLAYED) return result(Outcome.REVIEW, value, "CORE_DELIVERY_REQUIRES_REVIEW:" + receipt.status());
        try {
            var received = organization.unit(value.command().unitId()).orElseThrow().currentOrder().orElseThrow();
            var next = value.received(received); records.put(id, next); journal.persist(next);
            return result(Outcome.WAITING, next, "received intent; execution awaits exact durable reception");
        } catch (IllegalArgumentException exception) { return result(Outcome.REVIEW, value, "FOREIGN_OR_EARLY_CORE_RECEPTION"); }
    }
    public Optional<WarTransmission> effective(UUID unit, long now) {
        check(); var value = latest.get(unit); if (value == null) return Optional.empty();
        if (now < value.route().sentAt() || now < value.received().orElseThrow().deliveredAtTick()) return Optional.empty();
        if (organization.authorize(value.command().actorId(), value.command().armyId()) != WarCommandService.Permission.ALLOWED) return Optional.empty();
        return organization.unit(unit).filter(current -> current.armyId().equals(value.command().armyId()) && current.currentOrder().equals(value.received())).map(ignored -> value);
    }
    private void publish(WarTransmission value) {
        var unit = value.command().unitId(); var previous = latest.get(unit);
        if (previous == null || value.sequence() > previous.sequence()) {
            latest.put(unit, value);
            var slots = new HashMap<UUID, Integer>();
            for (int i = 0; i < value.members().size(); i++) slots.put(value.members().get(i).character(), i);
            memberSlots.put(unit, Map.copyOf(slots));
        }
        else if (value.sequence() == previous.sequence() && !value.equals(previous)) throw new IllegalArgumentException("conflicting received order sequence");
    }
    /** Slot in the exact latest received roster, after checking original person, contract and body. */
    public int memberSlot(WarTransmission received, WarTransmission.Member member) {
        check();
        UUID unit = received.command().unitId();
        if (latest.get(unit) != received) return -1;
        Integer slot = memberSlots.get(unit).get(member.character());
        return slot != null && received.members().get(slot).equals(member) ? slot : -1;
    }
    public record History(int hotRecords, long coldRecords, String problem) {}

    public History history() {
        check();
        return new History(records.size(),coldRecords,journal.archiveProblem());
    }

    /** Current and unresolved transmissions stay hot, as do any referenced by another execution ledger. */
    public Set<UUID> maintain(long now, Predicate<WarTransmission> pinned) {
        check();
        List<WarTransmission> candidates = List.of();
        if (records.size() >= ARCHIVE_HIGH_WATER && journal.wantsArchiveCandidates()) {
            candidates = records.values().stream().filter(row -> retireable(row,now) && !pinned.test(row))
                    .sorted(Comparator.comparingLong((WarTransmission row) -> row.received().orElseThrow().deliveredAtTick())
                            .thenComparing(row -> row.command().operationId()))
                    .limit(96).toList();
            if (candidates.size() < 32) candidates = List.of();
        }
        var retired = journal.maintain(candidates);
        for (var id : retired) records.remove(id);
        if (!retired.isEmpty()) coldRecords = journal.archivedCount();
        return retired;
    }

    private boolean retireable(WarTransmission row, long now) {
        var current = latest.get(row.command().unitId());
        if (current == null || current.sequence() <= row.sequence() || row.received().isEmpty()) return false;
        if (now - row.received().orElseThrow().deliveredAtTick() < RECENT_RECEPTION_TICKS
                || now - current.received().orElseThrow().deliveredAtTick() <= IN_FLIGHT_SAFETY_TICKS) return false;
        return organization.unit(current.command().unitId()).filter(u -> u.currentOrder().equals(current.received())).isPresent();
    }
    private static Result result(Outcome outcome, WarTransmission value, String reason) { return new Result(outcome, Optional.ofNullable(value), reason); }
    private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("dispatch owner thread required"); }
}
