package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.work.WorkCommitment;

/** 通用主劳动实体控制；共享桥不认识战争、工程或任何具体职业。仅服务端线程调用。 */
public interface NpcPrimaryWorkControl {
    void tick(NpcBase npc, WorkCommitment commitment, long now);
    default void stop(NpcBase npc) { npc.getNavigation().stop(); npc.setTarget(null); }
}
