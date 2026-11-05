package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 为一个 NPC 基于其主观上下文提出活动候选，不直接改变世界状态。
 */
public interface NpcActivityProvider {
    ResourceLocation id();

    List<NpcActivityCandidate> propose(NpcDecisionContext context);
}
