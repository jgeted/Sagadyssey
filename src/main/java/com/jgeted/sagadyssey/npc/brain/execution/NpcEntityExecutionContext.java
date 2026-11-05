package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.UUID;

/** 将行为执行器需要的最小接口连接到服务端 NPC 实体。 */
public record NpcEntityExecutionContext(NpcBase npc, ServerLevel level)
        implements NpcExecutionContext {
    public NpcEntityExecutionContext {
        Objects.requireNonNull(npc, "npc");
        Objects.requireNonNull(level, "level");
        if (npc.level() != level) {
            throw new IllegalArgumentException("npc is not in the supplied server level");
        }
    }

    @Override
    public UUID npcId() {
        return npc.getUUID();
    }

    @Override
    public long gameTime() {
        return level.getGameTime();
    }
}
