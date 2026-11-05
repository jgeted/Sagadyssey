package com.jgeted.sagadyssey.worldsim.lod;
import com.jgeted.sagadyssey.npc.ai.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.MinecraftServer;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Function;

/** Autonomous scans are processed independently of when the queue head's Goal polls again. */
public final class NpcPerceptionQueue {
    private record Request(WeakReference<NpcBase> actor,String variant,long submitted,Function<NpcBase,TargetResult> scan) {}
    private record Cached(String variant,long time,WeakReference<net.minecraft.world.entity.LivingEntity> target) {}
    private static final class State {final LinkedHashMap<UUID,Request> pending=new LinkedHashMap<>();final Map<UUID,Cached> results=new HashMap<>();long calls,maxWait,maxNanos;int peak,maxCount;}
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static State state(MinecraftServer s){return STATES.computeIfAbsent(s,k->new State());}
    public static TargetResult query(NpcBase n,String variant,Function<NpcBase,TargetResult> scan) {
        var s=n.getServer();var state=state(s);long now=s.overworld().getGameTime();var cached=state.results.get(n.getUUID());
        if(cached==null||!cached.variant().equals(variant)||now-cached.time()>=20) {
            if(state.pending.containsKey(n.getUUID())||state.pending.size()<8192)state.pending.putIfAbsent(n.getUUID(),new Request(new WeakReference<>(n),variant,now,scan));
            state.peak=Math.max(state.peak,state.pending.size());
        }
        if(cached!=null&&cached.variant().equals(variant)&&now-cached.time()<=40){var target=cached.target().get();if(target!=null)return new TargetResult(target,TargetReason.NEARBY_HOSTILE);}
        return null;
    }
    public static void forget(MinecraftServer s,UUID body){var state=state(s);state.pending.remove(body);state.results.remove(body);}
    public static void tick(MinecraftServer s) {
        var state=state(s);long start=System.nanoTime(),now=s.overworld().getGameTime();int count=0;
        while(!state.pending.isEmpty()&&count<12&&System.nanoTime()-start<1_000_000) {
            var entry=state.pending.firstEntry();state.pending.remove(entry.getKey());var r=entry.getValue();var n=r.actor().get();
            if(n==null||n.isRemoved()||!n.isAlive()||!NpcBrainRuntime.isPlanned(n)){state.results.remove(entry.getKey());continue;}
            var result=r.scan().apply(n);var target=result==null?null:result.target();state.results.put(n.getUUID(),new Cached(r.variant(),now,new WeakReference<>(target)));count++;state.calls++;state.maxWait=Math.max(state.maxWait,now-r.submitted());
        }
        state.maxCount=Math.max(state.maxCount,count);state.maxNanos=Math.max(state.maxNanos,System.nanoTime()-start);
        if(now%200==0)state.results.entrySet().removeIf(e->now-e.getValue().time()>200);
        LodMetrics.record(s,LodMetrics.Kind.PERCEPTION_SCAN,System.nanoTime()-start);
    }
    public record Snapshot(long calls,int backlog,int peak,int maxCount,long maxWaitTicks,long maxTickNanos) {}
    public static Snapshot snapshot(MinecraftServer s){var state=state(s);return new Snapshot(state.calls,state.pending.size(),state.peak,state.maxCount,state.maxWait,state.maxNanos);}
}
