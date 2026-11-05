package com.jgeted.sagadyssey.npc.brain.persistence;

/** 单个 NPC 的非战斗行为执行模式；旧存档与未知 NPC 始终从旧模式开始。 */
public enum NpcBehaviorMode {
    LEGACY,
    PLANNED
}
