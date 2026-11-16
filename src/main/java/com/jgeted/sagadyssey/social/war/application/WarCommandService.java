package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;

import java.util.*;

/**
 * 单一服务端线程持有的组织与军令用例。成功只表示组织记录受理或送达，
 * 不表示已动员人物、扣物、扣款或执行世界动作；这些属于后续正式适配。
 */
public final class WarCommandService {
    public enum Permission { ALLOWED, DENIED, UNAVAILABLE }

    @FunctionalInterface
    public interface Authority {
        /** 必须由可信调用上下文核验主体；客户端自报actorId不是授权证据。 */
        Permission check(UUID actorId, UUID armyId);
    }

    public record Limits(int maxUnits, int maxOperations, int maxPendingPerUnit) {
        public Limits {
            if (maxUnits < 1 || maxOperations < maxUnits || maxPendingPerUnit < 1) {
                throw new IllegalArgumentException("invalid war limits");
            }
        }
        public static Limits defaults() { return new Limits(128, 16_384, 32); }
    }

    private final Thread owner = Thread.currentThread();
    private final WarDefinitions definitions;
    private final Authority authority;
    private final Limits limits;
    private final long generation;
    private final Optional<WarArchiveRef> previousArchive;
    private final Map<UUID, WarState.Anchor> anchors;
    private final Map<UUID, WarUnit> units;
    private final Map<UUID, WarState.Operation> operations;
    private long lastChangedTick;

    public WarCommandService(WarDefinitions definitions, Authority authority, Limits limits, WarState state) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(state, "state");
        if (state.units().size() > limits.maxUnits() || state.operations().size() > limits.maxOperations()
                || state.units().values().stream().anyMatch(u -> u.pendingOrders().size() > limits.maxPendingPerUnit())) {
            throw new IllegalArgumentException("restored state exceeds configured limits; preserve it for review");
        }
        units = new HashMap<>(state.units());
        operations = new HashMap<>(state.operations());
        lastChangedTick = state.lastChangedTick();
        generation = state.generation(); previousArchive = state.previousArchive(); anchors = state.anchors();
    }

    public Optional<WarUnit> unit(UUID id) { checkThread(); return Optional.ofNullable(units.get(id)); }
    /** Read model: does not copy operation history or construct a persistence checkpoint. */
    public List<WarUnit> units() { checkThread(); return List.copyOf(units.values()); }
    public long generation() { checkThread(); return generation; }
    public int operationCount() { checkThread(); return operations.size(); }
    public WarState checkpoint() {
        checkThread(); return new WarState(lastChangedTick, units, operations, generation, previousArchive, anchors);
    }

    public WarResult submit(WarCommand command, long now) {
        checkThread();
        Objects.requireNonNull(command, "command");
        Permission permission = Objects.requireNonNull(authority.check(command.actorId(), command.armyId()));
        if (permission != Permission.ALLOWED) {
            return reject(permission == Permission.DENIED ? WarResult.Status.DENIED : WarResult.Status.UNAVAILABLE);
        }
        if (now < lastChangedTick) return reject(WarResult.Status.TIME_REVERSED);
        WarState.Operation previous = operations.get(command.operationId());
        if (previous != null) {
            return previous.command().equals(command) ? WarResult.replayed(previous.receipt())
                    : reject(WarResult.Status.OPERATION_CONFLICT);
        }
        // An archived request cannot become a new effect after its full receipt leaves hot memory.
        if (command.generation() < generation) return reject(WarResult.Status.RETIRED_GENERATION);
        if (command.generation() > generation) return reject(WarResult.Status.FUTURE_GENERATION);
        // No silent eviction: losing an accepted receipt must never turn a retry into a new effect.
        if (operations.size() >= limits.maxOperations()) return reject(WarResult.Status.CAPACITY_EXCEEDED);
        if (command instanceof WarCommand.CreateUnit create) return create(create, now);
        return issue((WarCommand.IssueOrder) command, now);
    }

    private WarResult create(WarCommand.CreateUnit command, long now) {
        if (units.containsKey(command.unitId())) return reject(WarResult.Status.ALREADY_EXISTS);
        if (units.size() >= limits.maxUnits()) return reject(WarResult.Status.CAPACITY_EXCEEDED);
        if (!definitions.supports(command.troopType(), WarDefinitions.Kind.TROOP, command.definitionVersion())) {
            return reject(WarResult.Status.UNSUPPORTED_DEFINITION);
        }
        WarUnit unit = new WarUnit(command.unitId(), command.armyId(), command.troopType(),
                command.definitionVersion(), 1, 0, Optional.empty(), List.of());
        return commit(command, unit, 0, now);
    }

    private WarResult issue(WarCommand.IssueOrder command, long now) {
        WarUnit unit = units.get(command.unitId());
        if (unit == null || !unit.armyId().equals(command.armyId())) return reject(WarResult.Status.NOT_FOUND);
        if (unit.revision() != command.expectedRevision()) return reject(WarResult.Status.STALE_REVISION);
        if (!supported(unit) || !supports(command.intent())) return reject(WarResult.Status.UNSUPPORTED_DEFINITION);
        if (unit.pendingOrders().size() >= limits.maxPendingPerUnit()
                || unit.revision() == Long.MAX_VALUE || unit.lastIssuedSequence() == Long.MAX_VALUE) {
            return reject(WarResult.Status.CAPACITY_EXCEEDED);
        }
        long sequence = unit.lastIssuedSequence() + 1;
        var pending = new ArrayList<>(unit.pendingOrders());
        pending.add(new WarUnit.Order(command.operationId(), command.generation(), command.actorId(), sequence,
                command.intent(), now, -1, 0));
        WarUnit next = new WarUnit(unit.id(), unit.armyId(), unit.troopType(), unit.definitionVersion(),
                unit.revision() + 1, sequence, unit.currentOrder(), pending);
        return commit(command, next, sequence, now);
    }

    /** 仅由可信传令执行器调用。客户端请求不能直接被当成已送达。 */
    public WarResult deliver(UUID unitId, UUID orderId, long now) {
        return deliver(unitId, 0, orderId, now);
    }

    public WarResult deliver(UUID unitId, long orderGeneration, UUID orderId, long now) {
        checkThread();
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(orderId, "orderId");
        if (now < lastChangedTick) return reject(WarResult.Status.TIME_REVERSED);
        WarUnit unit = units.get(unitId);
        WarState.Operation operation = operations.get(orderId);
        if (unit == null || operation == null || !(operation.command() instanceof WarCommand.IssueOrder issue)
                || !issue.unitId().equals(unitId) || issue.generation() != orderGeneration) return reject(WarResult.Status.NOT_FOUND);
        long deliveredSequence = unit.currentOrder().map(WarUnit.Order::sequence).orElse(0L);
        long sequence = operation.receipt().orderSequence();
        if (sequence < deliveredSequence) return reject(WarResult.Status.STALE_ORDER);
        if (sequence == deliveredSequence) {
            WarUnit.Order current = unit.currentOrder().orElseThrow();
            return WarResult.replayed(deliveryReceipt(unit.id(), current));
        }
        if (!supported(unit) || !supports(issue.intent())) return reject(WarResult.Status.UNSUPPORTED_DEFINITION);
        if (unit.revision() == Long.MAX_VALUE) return reject(WarResult.Status.CAPACITY_EXCEEDED);
        WarUnit.Order pending = unit.pendingOrders().stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow();
        long revision = unit.revision() + 1;
        WarUnit.Order current = new WarUnit.Order(pending.id(), pending.generation(), pending.issuerId(), sequence,
                pending.intent(), pending.issuedAtTick(), now, revision);
        List<WarUnit.Order> remaining = unit.pendingOrders().stream().filter(o -> o.sequence() > sequence).toList();
        units.put(unit.id(), new WarUnit(unit.id(), unit.armyId(), unit.troopType(), unit.definitionVersion(),
                revision, unit.lastIssuedSequence(), Optional.of(current), remaining));
        lastChangedTick = now;
        return WarResult.applied(deliveryReceipt(unit.id(), current));
    }

    private WarResult commit(WarCommand command, WarUnit unit, long sequence, long now) {
        var receipt = new WarResult.Receipt(command.operationId(), command.generation(), unit.id(), unit.revision(), sequence, now);
        units.put(unit.id(), unit);
        operations.put(command.operationId(), new WarState.Operation(command, receipt));
        lastChangedTick = now;
        return WarResult.applied(receipt);
    }

    private boolean supported(WarUnit unit) {
        return definitions.supports(unit.troopType(), WarDefinitions.Kind.TROOP, unit.definitionVersion());
    }
    private boolean supports(WarIntent intent) {
        return definitions.supports(intent.kind(), WarDefinitions.Kind.ORDER, intent.definitionVersion());
    }
    private static WarResult.Receipt deliveryReceipt(UUID unit, WarUnit.Order order) {
        return new WarResult.Receipt(order.id(), order.generation(), unit, order.deliveryRevision(), order.sequence(), order.deliveredAtTick());
    }
    private static WarResult reject(WarResult.Status status) { return WarResult.rejected(status); }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("war service accessed outside its owner thread");
    }
}
