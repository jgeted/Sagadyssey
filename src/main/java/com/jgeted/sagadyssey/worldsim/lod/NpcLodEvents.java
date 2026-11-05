package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class NpcLodEvents {
    private static final Map<MinecraftServer,Long> START=new WeakHashMap<>();
    @SubscribeEvent public static void begin(ServerTickEvent.Pre event){START.put(event.getServer(),System.nanoTime());NpcNavigationQueue.tick(event.getServer());NpcPerceptionQueue.tick(event.getServer());}
    @SubscribeEvent public static void end(ServerTickEvent.Post event){var s=event.getServer();NpcLodRuntime.summarize(s);var start=START.remove(s);if(start!=null)LodMetrics.record(s,LodMetrics.Kind.SERVER_TICK,System.nanoTime()-start);}
    @SubscribeEvent public static void join(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof NpcBase npc&&!event.getLevel().isClientSide()
                &&com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) {
            // Reconcile at deserialization/admission, before any goal or domain service can move cargo.
            // This also registers loaded NoAI originals so L1 never treats them as absent actors.
            NpcLodRuntime.loaded(npc);
        }
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof NpcBase npc&&!event.getLevel().isClientSide()) {
            var reason=npc.getRemovalReason();NpcLodRuntime.unload(npc,reason!=null&&reason.shouldDestroy());
        }
    }
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        // Normal stop precedes world saving. Persist the final committed boundary, not the first
        // checkpoint from admission. This is a one-off save operation, not a per-tick world scan.
        var server=event.getServer();var data=NpcLodData.get(server);
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities())
            if(entity instanceof NpcBase npc&&!npc.isRemoved()
                    &&com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                    &&!NpcLodRuntime.review(data.find(npc.getUUID()))) {
                data.put(NpcLodRuntime.capture(npc,true,"STOPPED: 原实体与最终提交边界保存，重进先对账再执行"));
                com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.suspend(npc);
            }
    }
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.forgetServer(event.getServer());
    }
}
