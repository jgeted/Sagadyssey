package com.jgeted.sagadyssey.worldsim.lod;

import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Bounded main-thread measurements. Project payload bytes exclude vanilla protocol traffic. */
public final class LodMetrics {
    public enum Kind { SERVER_TICK, SELECTION, PERCEPTION, PERCEPTION_SCAN, NAVIGATION, OFFLINE, SETTLEMENT, WORLD, REGION_ECONOMY, REGION_HANDOFF, REGION_TRADE }
    private static final Map<MinecraftServer,EnumMap<Kind,Series>> DATA=new WeakHashMap<>();
    private static final Map<MinecraftServer,long[]> BYTES=new WeakHashMap<>();
    private static final class Series {long[] values=new long[4096];long calls,max,slow;int size,index;final ArrayDeque<Long> slowTicks=new ArrayDeque<>();}
    public static void record(MinecraftServer server,Kind kind,long nanos) {
        var s=DATA.computeIfAbsent(server,k->new EnumMap<>(Kind.class)).computeIfAbsent(kind,k->new Series());
        long value=Math.max(0,nanos);s.values[s.index++%s.values.length]=value;s.size=Math.min(s.size+1,s.values.length);s.calls++;s.max=Math.max(s.max,value);
        if(kind==Kind.SERVER_TICK&&value>=50_000_000){s.slow++;if(s.slowTicks.size()==64)s.slowTicks.removeFirst();s.slowTicks.addLast(server.overworld().getGameTime());if(s.slow<=8)com.jgeted.sagadyssey.Sagadyssey.LOGGER.warn("LOD_SLOW_TICK gameTime={} nanos={}",server.overworld().getGameTime(),value);}
    }
    public static Snapshot snapshot(MinecraftServer server,Kind kind) {
        var s=DATA.getOrDefault(server,new EnumMap<>(Kind.class)).get(kind);if(s==null)return new Snapshot(0,0,0,0,0,List.of());
        var sorted=Arrays.copyOf(s.values,s.size);Arrays.sort(sorted);return new Snapshot(s.calls,s.size,sorted[(int)Math.ceil(s.size*.95)-1],s.max,s.slow,List.copyOf(s.slowTicks));
    }
    public static void reset(MinecraftServer server){DATA.remove(server);BYTES.remove(server);}
    public static void payload(MinecraftServer server,int bytes) {var counts=BYTES.computeIfAbsent(server,k->new long[3]);counts[0]++;counts[1]+=bytes;counts[2]=Math.max(counts[2],bytes);}
    public static String payloads(MinecraftServer server) {var c=BYTES.getOrDefault(server,new long[3]);return "NPC上下文响应 count="+c[0]+", encodedBytes="+c[1]+", max="+c[2]+"（不含原版流量）";}
    public record Snapshot(long calls,int samples,long p95Nanos,long maxNanos,long over50ms,List<Long> slowTicks) {}
}
