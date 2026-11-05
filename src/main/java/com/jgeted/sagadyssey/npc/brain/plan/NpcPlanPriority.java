package com.jgeted.sagadyssey.npc.brain.plan;

/** 计划的硬优先级层；层级比较先于同层效用评分。 */
public enum NpcPlanPriority {
    CRITICAL(4),
    HIGH(3),
    NORMAL(2),
    LOW(1),
    AMBIENT(0);

    private final int rank;

    NpcPlanPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isHigherThan(NpcPlanPriority other) {
        if (other == null) throw new IllegalArgumentException("other priority is required");
        return rank > other.rank;
    }
}
