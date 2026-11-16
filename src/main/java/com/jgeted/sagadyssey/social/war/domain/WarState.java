package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarCommand;
import com.jgeted.sagadyssey.social.war.api.WarResult;

import java.util.*;

/** 显式检查点。全量复制只在保存或检查点时发生，不用于每个tick的查询。 */
public record WarState(long lastChangedTick, Map<UUID, WarUnit> units, Map<UUID, Operation> operations,
                       long generation, Optional<WarArchiveRef> previousArchive, Map<UUID, Anchor> anchors) {
    public record Anchor(long revision, long lastIssuedSequence) {
        public Anchor {
            if (revision < 1 || lastIssuedSequence < 0) throw new IllegalArgumentException("invalid archive anchor");
        }
    }

    /** 首版schema 1与未归档状态从第0代开始。 */
    public WarState(long lastChangedTick, Map<UUID, WarUnit> units, Map<UUID, Operation> operations) {
        this(lastChangedTick, units, operations, 0, Optional.empty(), Map.of());
    }

    public WarState {
        units = Map.copyOf(units);
        operations = Map.copyOf(operations);
        anchors = Map.copyOf(anchors);
        Objects.requireNonNull(previousArchive, "previousArchive");
        if (lastChangedTick < 0 || generation < 0 || (generation == 0) != previousArchive.isEmpty()
                || generation == 0 && !anchors.isEmpty()
                || previousArchive.isPresent() && (previousArchive.get().generation() != generation - 1
                || previousArchive.get().checkpointTick() > lastChangedTick)) {
            throw new IllegalArgumentException("invalid state time or archive generation");
        }
        for (var entry : units.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("unit key mismatch");
        }
        for (var entry : anchors.entrySet()) {
            WarUnit unit = units.get(entry.getKey());
            if (unit == null || entry.getValue().revision() > unit.revision()
                    || entry.getValue().lastIssuedSequence() > unit.lastIssuedSequence()) {
                throw new IllegalArgumentException("invalid archive unit anchor");
            }
        }
        Map<UUID, Operation> creations = new HashMap<>();
        Map<UUID, SortedMap<Long, Operation>> histories = new HashMap<>();
        for (var entry : operations.entrySet()) {
            Operation operation = entry.getValue();
            WarUnit unit = units.get(operation.command().unitId());
            if (!entry.getKey().equals(operation.command().operationId()) || unit == null
                    || !unit.armyId().equals(operation.command().armyId())
                    || operation.receipt().unitRevision() > unit.revision()
                    || operation.receipt().appliedAtTick() > lastChangedTick
                    || operation.receipt().orderSequence() > unit.lastIssuedSequence()
                    || operation.command().generation() > generation) {
                throw new IllegalArgumentException("orphan or inconsistent operation receipt");
            }
            if (operation.command() instanceof WarCommand.CreateUnit create
                    && (!unit.troopType().equals(create.troopType())
                    || unit.definitionVersion() != create.definitionVersion()
                    || creations.putIfAbsent(create.unitId(), operation) != null)) {
                throw new IllegalArgumentException("creation definition mismatch");
            }
            if (operation.command() instanceof WarCommand.IssueOrder
                    && histories.computeIfAbsent(unit.id(), ignored -> new TreeMap<>())
                    .putIfAbsent(operation.receipt().orderSequence(), operation) != null) {
                throw new IllegalArgumentException("duplicate accepted order sequence");
            }
        }
        for (WarUnit unit : units.values()) {
            if (!creations.containsKey(unit.id())) {
                throw new IllegalArgumentException("unit has no creation receipt");
            }
            Anchor anchor = anchors.get(unit.id());
            Operation creation = creations.get(unit.id());
            if (anchor == null ? creation.command().generation() != generation
                    : creation.command().generation() >= generation
                    || creation.receipt().appliedAtTick() > previousArchive.orElseThrow().checkpointTick()) {
                throw new IllegalArgumentException("creation and archive generation disagree");
            }
            validateHistory(unit, creation, histories.getOrDefault(unit.id(), Collections.emptySortedMap()),
                    anchor, generation, previousArchive.map(WarArchiveRef::checkpointTick).orElse(0L));
            List<WarUnit.Order> orders = new ArrayList<>(unit.pendingOrders());
            unit.currentOrder().ifPresent(orders::add);
            for (WarUnit.Order order : orders) {
                Operation operation = operations.get(order.id());
                if (operation == null || !(operation.command() instanceof WarCommand.IssueOrder issue)
                        || !issue.unitId().equals(unit.id()) || !issue.actorId().equals(order.issuerId())
                        || issue.generation() != order.generation()
                        || !issue.intent().equals(order.intent())
                        || operation.receipt().orderSequence() != order.sequence()
                        || operation.receipt().appliedAtTick() != order.issuedAtTick()
                        || order.deliveryRevision() > 0 && order.deliveryRevision() <= operation.receipt().unitRevision()
                        || order.deliveredAtTick() > lastChangedTick) {
                    throw new IllegalArgumentException("order does not match acceptance receipt");
                }
            }
        }
    }

    private static void validateHistory(WarUnit unit, Operation creation, SortedMap<Long, Operation> history,
                                        Anchor anchor, long generation, long archivedAt) {
        long floor = anchor == null ? 0 : anchor.lastIssuedSequence();
        long sequence = floor, revision = anchor == null ? 1 : anchor.revision();
        long tick = anchor == null ? creation.receipt().appliedAtTick() : archivedAt;
        for (var entry : history.entrySet()) {
            WarResult.Receipt receipt = entry.getValue().receipt();
            if (entry.getKey() <= floor) {
                if (entry.getValue().command().generation() >= generation || receipt.unitRevision() > revision
                        || receipt.appliedAtTick() > archivedAt) throw new IllegalArgumentException("invalid archived acceptance");
                continue;
            }
            if (entry.getKey() != ++sequence || receipt.unitRevision() <= revision || receipt.appliedAtTick() < tick
                    || entry.getValue().command().generation() != generation
                    || unit.currentOrder().isEmpty() && receipt.unitRevision() != sequence + 1) {
                throw new IllegalArgumentException("non-monotonic accepted order history");
            }
            if (unit.currentOrder().isPresent()) {
                WarUnit.Order delivered = unit.currentOrder().get();
                if (receipt.unitRevision() == delivered.deliveryRevision()
                        || receipt.unitRevision() < delivered.deliveryRevision() && receipt.appliedAtTick() > delivered.deliveredAtTick()
                        || receipt.unitRevision() > delivered.deliveryRevision() && receipt.appliedAtTick() < delivered.deliveredAtTick()) {
                    throw new IllegalArgumentException("acceptance and delivery history disagree");
                }
            }
            revision = receipt.unitRevision(); tick = receipt.appliedAtTick();
        }
        if (sequence != unit.lastIssuedSequence()) throw new IllegalArgumentException("incomplete accepted order history");
        long latestRevision = Math.max(revision, unit.currentOrder().map(WarUnit.Order::deliveryRevision).orElse(1L));
        if (latestRevision != unit.revision()) throw new IllegalArgumentException("unexplained unit revision");
    }

    public static WarState empty() { return new WarState(0, Map.of(), Map.of()); }

    public int archivableOperationCount() { return operations.size() - pinnedOperationIds().size(); }

    private Set<UUID> pinnedOperationIds() {
        Set<UUID> pinned = new HashSet<>();
        operations.forEach((id, operation) -> { if (operation.command() instanceof WarCommand.CreateUnit) pinned.add(id); });
        units.values().forEach(unit -> {
            unit.currentOrder().ifPresent(order -> pinned.add(order.id()));
            unit.pendingOrders().forEach(order -> pinned.add(order.id()));
        });
        return pinned;
    }

    /** 调用方先持久化并核验整个旧检查点，才可发布此压缩状态。 */
    public WarState compact(WarArchiveRef archive) {
        if (generation == Long.MAX_VALUE || archive.generation() != generation || archive.checkpointTick() != lastChangedTick) {
            throw new IllegalArgumentException("archive does not describe this checkpoint");
        }
        Set<UUID> pinned = pinnedOperationIds();
        Map<UUID, Anchor> nextAnchors = new HashMap<>();
        for (WarUnit unit : units.values()) {
            nextAnchors.put(unit.id(), new Anchor(unit.revision(), unit.lastIssuedSequence()));
        }
        Map<UUID, Operation> retained = new HashMap<>();
        operations.forEach((id, operation) -> {
            if (pinned.contains(id)) retained.put(id, operation);
        });
        return new WarState(lastChangedTick, units, retained, generation + 1, Optional.of(archive), nextAnchors);
    }

    public record Operation(WarCommand command, WarResult.Receipt receipt) {
        public Operation {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(receipt, "receipt");
            if (!command.operationId().equals(receipt.operationId()) || !command.unitId().equals(receipt.unitId())
                    || command.generation() != receipt.generation()
                    || command instanceof WarCommand.CreateUnit && (receipt.unitRevision() != 1 || receipt.orderSequence() != 0)
                    || command instanceof WarCommand.IssueOrder issue && (receipt.orderSequence() < 1
                    || issue.expectedRevision() == Long.MAX_VALUE
                    || receipt.unitRevision() != issue.expectedRevision() + 1)) {
                throw new IllegalArgumentException("receipt does not match command");
            }
        }
    }
}
