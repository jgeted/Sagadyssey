package com.jgeted.sagadyssey.npc.brain.plan;

/** 一个执行步骤在计划生命周期中的语义阶段。 */
public enum NpcPlanPhase {
    RESERVING(NpcPlanStatus.RESERVING),
    TRAVELLING(NpcPlanStatus.TRAVELLING),
    ACTING(NpcPlanStatus.ACTING),
    SETTLING(NpcPlanStatus.SETTLING);

    private final NpcPlanStatus planStatus;

    NpcPlanPhase(NpcPlanStatus planStatus) {
        this.planStatus = planStatus;
    }

    public NpcPlanStatus planStatus() {
        return planStatus;
    }

    public boolean canFollow(NpcPlanPhase previous) {
        return previous == null || ordinal() >= previous.ordinal();
    }
}
