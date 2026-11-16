package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.jgeted.sagadyssey.social.war.api.WarResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCommandServiceTest {
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ARMY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static WarCommandService service() {
        return service(WarState.empty(), WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
    }
    static WarCommandService service(WarState state, WarCommandService.Limits limits, WarDefinitions definitions) {
        return new WarCommandService(definitions, (actor, army) -> actor.equals(ACTOR) && army.equals(ARMY)
                ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED, limits, state);
    }
    static WarCommand.CreateUnit create() {
        return new WarCommand.CreateUnit(UUID.randomUUID(), ACTOR, ARMY, UNIT, InitialWarDefinitions.TROOPS.getFirst(), 1);
    }
    static WarCommand.IssueOrder order(WarCommandService service, int x) {
        return new WarCommand.IssueOrder(UUID.randomUUID(), service.generation(), ACTOR, ARMY, UNIT, service.unit(UNIT).orElseThrow().revision(),
                new WarIntent(InitialWarDefinitions.MOVE, 1, new WarIntent.Position(WarKey.parse("minecraft:overworld"), x, 64, 0)));
    }

    @Test void submissionDoesNotReplaceAnOrderUntilDeliveredAndLateOrdersCannotUndoIt() {
        var service = service();
        assertEquals(APPLIED, service.submit(create(), 10).status());
        var first = order(service, 10);
        assertEquals(1, service.submit(first, 11).receipt().orElseThrow().orderSequence());
        assertTrue(service.unit(UNIT).orElseThrow().currentOrder().isEmpty());
        assertEquals(APPLIED, service.deliver(UNIT, first.operationId(), 12).status());
        var second = order(service, 20);
        service.submit(second, 13);
        var third = order(service, 30);
        service.submit(third, 14);
        assertEquals(first.operationId(), service.unit(UNIT).orElseThrow().currentOrder().orElseThrow().id());
        assertEquals(APPLIED, service.deliver(UNIT, third.operationId(), 15).status());
        WarState beforeLateArrival = service.checkpoint();
        assertEquals(STALE_ORDER, service.deliver(UNIT, second.operationId(), 16).status());
        assertEquals(beforeLateArrival, service.checkpoint());
        assertEquals(3, service.unit(UNIT).orElseThrow().currentOrder().orElseThrow().sequence());
        assertTrue(service.unit(UNIT).orElseThrow().pendingOrders().isEmpty());
    }

    @Test void retryReturnsOriginalReceiptAndChangedContentCannotReuseItsId() {
        var service = service(); var create = create();
        WarResult original = service.submit(create, 1);
        assertEquals(original.receipt(), service.submit(create, 2).receipt());
        assertEquals(REPLAYED, service.submit(create, 2).status());
        var changed = new WarCommand.CreateUnit(create.operationId(), ACTOR, ARMY, UNIT,
                InitialWarDefinitions.TROOPS.get(1), 1);
        assertEquals(OPERATION_CONFLICT, service.submit(changed, 2).status());
        assertEquals(1, service.checkpoint().operations().size());
    }

    @Test void retrySurvivesRestorationEvenAfterOtherMutationsAndAFullJournal() {
        var limits = new WarCommandService.Limits(1, 2, 2);
        var service = service(WarState.empty(), limits, InitialWarDefinitions.create());
        var create = create(); service.submit(create, 1);
        var order = order(service, 5); var accepted = service.submit(order, 2).receipt();
        var delivered = service.deliver(UNIT, order.operationId(), 3).receipt();
        var restored = service(service.checkpoint(), limits, InitialWarDefinitions.create());
        assertEquals(REPLAYED, restored.submit(order, 10).status());
        assertEquals(accepted, restored.submit(order, 10).receipt());
        assertEquals(delivered, restored.deliver(UNIT, order.operationId(), 11).receipt());
        assertEquals(CAPACITY_EXCEEDED, restored.submit(order(restored, 6), 12).status());
        assertEquals(2, restored.checkpoint().operations().size());
    }

    @Test void revisionsAndAuthorityAreCheckedWithoutCreatingPhantomState() {
        var service = service();
        var create = create(); service.submit(create, 1);
        var first = order(service, 1); var stale = order(service, 2);
        service.submit(first, 2);
        assertEquals(STALE_REVISION, service.submit(stale, 3).status());
        var denied = new WarCommand.CreateUnit(UUID.randomUUID(), UUID.randomUUID(), ARMY, UUID.randomUUID(), create.troopType(), 1);
        assertEquals(DENIED, service.submit(denied, 4).status());
        assertEquals(1, service.checkpoint().units().size());
        assertEquals(2, service.checkpoint().operations().size());
    }

    @Test void unavailableAuthorityAndUnknownDefinitionsFailClosed() {
        var service = new WarCommandService(InitialWarDefinitions.create(), (actor, army) -> WarCommandService.Permission.UNAVAILABLE,
                WarCommandService.Limits.defaults(), WarState.empty());
        assertEquals(UNAVAILABLE, service.submit(create(), 0).status());
        assertTrue(service.checkpoint().units().isEmpty());
        service = service();
        var unknown = new WarCommand.CreateUnit(UUID.randomUUID(), ACTOR, ARMY, UNIT, WarKey.parse("missing:troop"), 1);
        assertEquals(UNSUPPORTED_DEFINITION, service.submit(unknown, 0).status());
    }

    @Test void removingADefinitionPreservesTheUnitButPreventsExecution() {
        var service = service(); service.submit(create(), 1);
        var order = order(service, 5); service.submit(order, 2);
        WarState state = service.checkpoint();
        var restored = service(state, WarCommandService.Limits.defaults(), new WarDefinitions.Builder().freeze());
        assertEquals(UNSUPPORTED_DEFINITION, restored.deliver(UNIT, order.operationId(), 3).status());
        assertEquals(state, restored.checkpoint());
    }

    @Test void pendingCapacityDoesNotDiscardTheCurrentOrOlderAcceptedOrders() {
        var service = service(WarState.empty(), new WarCommandService.Limits(1, 10, 1), InitialWarDefinitions.create());
        service.submit(create(), 0);
        var first = order(service, 1); service.submit(first, 1);
        assertEquals(CAPACITY_EXCEEDED, service.submit(order(service, 2), 2).status());
        service.deliver(UNIT, first.operationId(), 3);
        assertEquals(APPLIED, service.submit(order(service, 3), 4).status());
        assertEquals(first.operationId(), service.unit(UNIT).orElseThrow().currentOrder().orElseThrow().id());
    }

    @Test void clocksCannotReverseAndOtherThreadsCannotMutateTheService() {
        var service = service(); service.submit(create(), 5);
        assertEquals(TIME_REVERSED, service.submit(order(service, 1), 4).status());
        var request = order(service, 1);
        var thrown = CompletableFuture.supplyAsync(() -> assertThrows(IllegalStateException.class,
                () -> service.submit(request, 6))).join();
        assertTrue(thrown.getMessage().contains("owner thread"));
        assertEquals(1, service.checkpoint().operations().size());
    }

    @Test void snapshotsCannotBeUsedToMutateLiveState() {
        var service = service(); service.submit(create(), 1);
        WarState snapshot = service.checkpoint();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.units().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.unit(UNIT).orElseThrow().pendingOrders().clear());
    }
}
