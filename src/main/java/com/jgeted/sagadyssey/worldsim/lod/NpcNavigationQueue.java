package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.lang.ref.WeakReference;
import java.util.*;

/** Bounded FIFO path requests. Admission means queued, never means arrival or successful work. */
public final class NpcNavigationQueue {
    private record Request(WeakReference<NpcBase> entity,UUID plan,int step,NpcCommand command,BlockPos target,double speed) {}
    private static final Map<MinecraftServer,LinkedHashMap<UUID,Request>> QUEUES=new WeakHashMap<>();
    private static LinkedHashMap<UUID,Request> queue(MinecraftServer s){return QUEUES.computeIfAbsent(s,k->new LinkedHashMap<>());}
    public static boolean move(NpcBase npc,BlockPos target,double speed) {
        var s=npc.getServer();if(s==null||!NpcBrainRuntime.isPlanned(npc))return npc.getNavigation().moveTo(target.getX()+.5,target.getY(),target.getZ()+.5,speed);
        var plan=NpcBrainRuntime.data(s).active(npc.getUUID()).orElse(null);if(plan==null)return false;
        var q=queue(s);if(!q.containsKey(npc.getUUID())&&q.size()>=8192)return false;
        q.put(npc.getUUID(),new Request(new WeakReference<>(npc),plan.id(),plan.currentStepIndex(),npc.getCommand(),target.immutable(),speed));return true;
    }
    public static int backlog(MinecraftServer s){return queue(s).size();}
    public static void forget(MinecraftServer s,UUID body){queue(s).remove(body);}
    public static void tick(MinecraftServer s) {
        long start=System.nanoTime();var q=queue(s);int count=0;
        while(!q.isEmpty()&&count++<8&&System.nanoTime()-start<2_000_000) {
            var entry=q.firstEntry();q.remove(entry.getKey());var r=entry.getValue();var n=r.entity().get();
            if(n==null||!n.isAlive()||n.isRemoved()||n.getCommand()!=r.command()||n.getTarget()!=null||!NpcBrainRuntime.isPlanned(n))continue;
            var plan=NpcBrainRuntime.data(s).active(n.getUUID()).orElse(null);
            if(plan==null||!plan.id().equals(r.plan())||plan.currentStepIndex()!=r.step()||!n.level().hasChunkAt(r.target())||NpcLodRuntime.review(NpcLodData.get(s).find(n.getUUID())))continue;
            n.getNavigation().moveTo(r.target().getX()+.5,r.target().getY(),r.target().getZ()+.5,r.speed());
        }
        LodMetrics.record(s,LodMetrics.Kind.NAVIGATION,System.nanoTime()-start);
    }
}
