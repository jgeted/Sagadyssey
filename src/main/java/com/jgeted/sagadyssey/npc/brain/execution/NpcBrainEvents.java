package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** 把不可恢复的实体生命周期事件提交到计划状态机。 */
public final class NpcBrainEvents {
    private NpcBrainEvents() {
    }

    @SubscribeEvent
    public static void onNpcDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof NpcBase npc) || MinecraftApi.server(npc) == null) return;
        if (NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).isEmpty()) return;
        NpcBrainRuntime.cancel(npc, PlanCancellationReason.NPC_DEATH,
                "NPC死亡，活动计划终止");
    }
}
