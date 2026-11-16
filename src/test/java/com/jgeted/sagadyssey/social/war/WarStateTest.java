package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStateCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.WarCommandServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WarStateTest {
    @Test void supersededAcceptanceEvidenceMustStillBeCompleteAndUnique() {
        var service = service(); service.submit(create(), 0);
        var first = order(service, 1); service.submit(first, 1);
        var second = order(service, 2); service.submit(second, 2);
        var third = order(service, 3); service.submit(third, 3);
        service.deliver(UNIT, third.operationId(), 4);
        WarState state = service.checkpoint();
        var missing = new HashMap<>(state.operations()); missing.remove(second.operationId());
        assertThrows(IllegalArgumentException.class, () -> new WarState(state.lastChangedTick(), state.units(), missing));
        var duplicate = new HashMap<>(state.operations());
        var receipt = duplicate.get(second.operationId()).receipt();
        duplicate.put(second.operationId(), new WarState.Operation(second, new WarResult.Receipt(
                receipt.operationId(), UNIT, receipt.unitRevision(), 1, receipt.appliedAtTick())));
        assertThrows(IllegalArgumentException.class, () -> new WarState(state.lastChangedTick(), state.units(), duplicate));
    }

    @Test void deliveryCannotPrecedeAcceptanceAndRevisionsMustHaveEvidence() {
        var service = service(); service.submit(create(), 0);
        var command = order(service, 1); service.submit(command, 1); service.deliver(UNIT, command.operationId(), 2);
        WarState state = service.checkpoint(); WarUnit unit = state.units().get(UNIT);
        WarUnit.Order current = unit.currentOrder().orElseThrow();
        var early = new WarUnit.Order(current.id(), current.issuerId(), current.sequence(), current.intent(),
                current.issuedAtTick(), current.deliveredAtTick(), 2);
        var invalidDelivery = new WarUnit(unit.id(), unit.armyId(), unit.troopType(), unit.definitionVersion(),
                2, unit.lastIssuedSequence(), Optional.of(early), List.of());
        assertThrows(IllegalArgumentException.class, () -> new WarState(state.lastChangedTick(),
                Map.of(UNIT, invalidDelivery), state.operations()));
        var unexplained = new WarUnit(unit.id(), unit.armyId(), unit.troopType(), unit.definitionVersion(),
                unit.revision() + 1, unit.lastIssuedSequence(), unit.currentOrder(), unit.pendingOrders());
        assertThrows(IllegalArgumentException.class, () -> new WarState(state.lastChangedTick(),
                Map.of(UNIT, unexplained), state.operations()));
    }

    @Test void repeatedReloadsAndArbitraryDeliveryOrderNeverRegressTheCurrentOrder() throws Exception {
        var random = new Random(71_029);
        var codec = new WarStateCodec(WarStateCodec.Limits.defaults());
        var service = service(); service.submit(create(), 0);
        List<WarCommand.IssueOrder> accepted = new ArrayList<>();
        long highestDelivered = 0;
        for (int tick = 1; tick <= 400; tick++) {
            if (accepted.isEmpty() || random.nextBoolean()) {
                var command = order(service, tick);
                if (service.submit(command, tick).status() == WarResult.Status.APPLIED) accepted.add(command);
            } else {
                var command = accepted.get(random.nextInt(accepted.size()));
                long sequence = service.checkpoint().operations().get(command.operationId()).receipt().orderSequence();
                WarResult result = service.deliver(UNIT, command.operationId(), tick);
                var expected = sequence < highestDelivered ? WarResult.Status.STALE_ORDER
                        : sequence == highestDelivered ? WarResult.Status.REPLAYED : WarResult.Status.APPLIED;
                assertEquals(expected, result.status());
                highestDelivered = Math.max(highestDelivered, sequence);
            }
            assertEquals(highestDelivered, service.unit(UNIT).orElseThrow().currentOrder()
                    .map(WarUnit.Order::sequence).orElse(0L));
            if (tick % 13 == 0) {
                var restored = assertInstanceOf(WarStateCodec.Restored.class, codec.read(codec.write(service.checkpoint())));
                service = service(restored.state(), WarCommandService.Limits.defaults(),
                        InitialWarDefinitions.create());
                var previous = accepted.get(random.nextInt(accepted.size()));
                assertEquals(WarResult.Status.REPLAYED, service.submit(previous, tick).status());
            }
        }
    }
}
