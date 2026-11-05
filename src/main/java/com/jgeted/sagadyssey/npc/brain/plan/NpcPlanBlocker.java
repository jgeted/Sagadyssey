package com.jgeted.sagadyssey.npc.brain.plan;

import javax.annotation.Nullable;

/** 可诊断、可重试的结构化阻塞原因。 */
public record NpcPlanBlocker(String code, String detail, long retryAt,
                             @Nullable String dependencyKey) {
    public NpcPlanBlocker {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("blocker code is required");
        }
        if (retryAt < 0L) throw new IllegalArgumentException("retryAt cannot be negative");
        detail = detail == null ? "" : detail;
        if (dependencyKey != null && dependencyKey.isBlank()) {
            throw new IllegalArgumentException("dependencyKey cannot be blank");
        }
    }
}
