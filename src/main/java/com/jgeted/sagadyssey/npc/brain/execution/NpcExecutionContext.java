package com.jgeted.sagadyssey.npc.brain.execution;

import java.util.UUID;

/**
 * 执行器可依赖的最小 NPC 上下文，避免行为内核直接绑定实体实现。
 */
public interface NpcExecutionContext {
    UUID npcId();

    long gameTime();
}
