package com.jgeted.sagadyssey.npc.brain.execution;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Bounded, transient timing samples. No SavedData writes or ordinary-player disclosure. */
public final class NpcPlanningMetrics {
    private NpcPlanningMetrics(){}
    private static final Map<MinecraftServer,Sample> SAMPLES=new WeakHashMap<>();
    private static final class Sample {final long[] times=new long[4096];long calls,total,max;int cursor;}
    public record Snapshot(long calls,long totalNanos,long maxNanos,long p95Nanos,int retained){}
    public static void record(MinecraftServer server,long nanos){var s=SAMPLES.computeIfAbsent(server,ignored->new Sample());s.times[s.cursor]=nanos;s.cursor=(s.cursor+1)%s.times.length;s.calls++;s.total+=nanos;s.max=Math.max(s.max,nanos);}
    public static Snapshot snapshot(MinecraftServer server){var s=SAMPLES.get(server);if(s==null)return new Snapshot(0,0,0,0,0);int n=(int)Math.min(s.calls,s.times.length);var sorted=Arrays.copyOf(s.times,n);Arrays.sort(sorted);return new Snapshot(s.calls,s.total,s.max,sorted[Math.max(0,(int)Math.ceil(n*.95)-1)],n);}
}
