package com.jgeted.sagadyssey.social.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceStatusTest {
    @Test
    void playerFacingStatusNeverLeaksInternalEnumNames() {
        assertEquals("生效中", ServiceStatus.ACTIVE.displayName());
        assertEquals("已暂停", ServiceStatus.SUSPENDED.displayName());
        assertEquals("已完成", ServiceStatus.COMPLETED.displayName());
        assertEquals("已解除", ServiceStatus.CANCELLED.displayName());
        assertEquals("已违约", ServiceStatus.BREACHED.displayName());
    }
}
