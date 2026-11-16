package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarJointOrderCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WarJointOrderTest {
    @Test
    void eachCommanderSignsOwnOrdersAndReportAccessEndsWithoutDiscardingReceipts() throws Exception {
        var a = UUID.randomUUID();
        var c = UUID.randomUUID();
        var plan = WarJointOrder.draft(UUID.randomUUID(), a, c, 100, 200);
        var first = plan.add(order(plan.id(), a, false));
        assertThrows(IllegalArgumentException.class, () -> first.sign(a));
        var selected = first.add(order(plan.id(), c, true));
        var signed = selected.sign(a);
        assertEquals(Phase.DRAFT, signed.phase());
        assertThrows(IllegalStateException.class, () -> signed.add(order(plan.id(), a, true)));
        assertThrows(IllegalStateException.class, () -> signed.sign(UUID.randomUUID()));
        var scheduled = signed.sign(c);
        assertTrue(WarJointOrder.follows(signed, scheduled));
        var issued = scheduled.fence();
        assertEquals(Phase.STOPPED, issued.close().phase());
        assertTrue(WarJointOrder.follows(issued, issued.close()));
        var results = issued.instructions().stream().map(leg -> new Receipt(leg.command().operationId(), Outcome.ACCEPTED, 105, "")).toList();
        var sent = issued.finish(results);
        assertTrue(sent.sharesReports(199));
        assertFalse(sent.sharesReports(200));
        var closed = sent.close();
        assertFalse(closed.sharesReports(110));
        assertEquals(results, closed.receipts());
        var codec = new WarJointOrderCodec();
        assertEquals(List.of(closed), codec.read(codec.write(List.of(closed))));
    }

    @Test
    void uncertaintyRetainsTheOriginalOperationAndPreventsPublishingReports() {
        var lord = UUID.randomUUID();
        var plan = WarJointOrder.draft(UUID.randomUUID(), lord, lord, 100, 200);
        var leg = order(plan.id(), lord, true);
        var issuing = plan.add(leg).sign(lord).fence();
        var unknown = new Receipt(leg.command().operationId(), Outcome.UNKNOWN, -1, "missing original receipt");
        var review = issuing.finish(List.of(unknown));
        assertEquals(Phase.REVIEW, review.phase());
        assertFalse(review.sharesReports(125));
        assertThrows(IllegalStateException.class, review::fence);
    }

    private static Instruction order(UUID plan, UUID owner, boolean share) {
        var map = new WarMapSheet(0, WarKey.parse("minecraft:overworld"), 0, 0, 0);
        var point = new WarIntent.Position(map.dimension(), map.x(64), 80, map.z(64));
        var unit = UUID.randomUUID();
        var command = new WarCommand.IssueOrder(WarJointOrder.operationId(plan, unit),
                owner, UUID.randomUUID(), unit, 1, new WarIntent(WarKey.parse("sagadyssey:war/hold"), 1, point));
        return new Instruction(command, map, 64, 64, share);
    }
}
