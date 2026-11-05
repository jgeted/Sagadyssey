package com.jgeted.sagadyssey.npc.brain.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 每个 NPC 只允许一个活动计划的持久化边界。 */
public interface NpcPlanStore {
    Optional<NpcPlan> active(UUID npcId);

    List<NpcPlan> history(UUID npcId);

    /** 新计划、活动计划快照和终态快照都通过同一入口提交。 */
    void save(NpcPlan plan);
}
