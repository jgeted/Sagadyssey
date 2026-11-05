package com.jgeted.sagadyssey.social.needs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeedRequestStatusTest {
    @Test void normalFulfillmentRequiresDeliveryBeforeConsumption() {
        assertTrue(NeedRequestStatus.OPEN.canTransitionTo(NeedRequestStatus.MATCHED));
        assertTrue(NeedRequestStatus.MATCHED.canTransitionTo(NeedRequestStatus.IN_TRANSIT));
        assertTrue(NeedRequestStatus.IN_TRANSIT.canTransitionTo(NeedRequestStatus.DELIVERED));
        assertTrue(NeedRequestStatus.DELIVERED.canTransitionTo(NeedRequestStatus.CONSUMED));
        assertTrue(NeedRequestStatus.DELIVERED.canTransitionTo(NeedRequestStatus.CLOSED));
        assertFalse(NeedRequestStatus.OPEN.canTransitionTo(NeedRequestStatus.DELIVERED));
        assertFalse(NeedRequestStatus.MATCHED.canTransitionTo(NeedRequestStatus.CONSUMED));
    }

    @Test void terminalRequestsCannotBeReopenedOrCancelledAgain() {
        for (NeedRequestStatus status : NeedRequestStatus.values()) {
            if (status.terminal()) {
                for (NeedRequestStatus next : NeedRequestStatus.values()) {
                    assertFalse(status.canTransitionTo(next));
                }
            }
            assertFalse(status.canTransitionTo(status));
            assertFalse(status.canTransitionTo(null));
        }
    }

    @Test void blockedRequestsRecoverThroughRevalidationNotDirectFulfillment() {
        for (NeedRequestStatus status : new NeedRequestStatus[] {
                NeedRequestStatus.WAITING_EXTERNAL, NeedRequestStatus.UNREACHABLE,
                NeedRequestStatus.BLOCKED_CYCLE}) {
            assertFalse(status.terminal());
            assertTrue(status.canTransitionTo(NeedRequestStatus.OPEN));
            assertFalse(status.canTransitionTo(NeedRequestStatus.DELIVERED));
            assertFalse(status.canTransitionTo(NeedRequestStatus.IN_TRANSIT));
        }
        assertFalse(NeedRequestStatus.IN_TRANSIT.canTransitionTo(NeedRequestStatus.OPEN));
        assertFalse(NeedRequestStatus.IN_TRANSIT.canTransitionTo(NeedRequestStatus.WAITING_EXTERNAL));
        assertTrue(NeedRequestStatus.IN_TRANSIT.canTransitionTo(NeedRequestStatus.UNREACHABLE));
    }
}
