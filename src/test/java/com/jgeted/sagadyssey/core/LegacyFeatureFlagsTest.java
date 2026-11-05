package com.jgeted.sagadyssey.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacyFeatureFlagsTest {
    @Test
    void legacyResearchRemainsDisabled() {
        assertFalse(LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED,
                "旧研究系统尚未迁移，不应重新暴露玩家入口");
    }
}
