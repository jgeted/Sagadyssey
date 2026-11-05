package com.jgeted.sagadyssey.npc.brain.execution;

/**
 * 已承诺计划被终止的结构化原因。
 */
public enum PlanCancellationReason {
    THREAT,
    URGENT_NEED,
    SCHEDULE_CHANGE,
    AUTHORIZED_COMMAND,
    TARGET_INVALID,
    PATH_FAILED,
    CHUNK_UNLOAD,
    NPC_DEATH,
    SERVER_STOP,
    MANUAL
}
