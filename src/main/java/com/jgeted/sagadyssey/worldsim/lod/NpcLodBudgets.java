package com.jgeted.sagadyssey.worldsim.lod;

import net.minecraft.server.MinecraftServer;
import java.util.Map;
import java.util.WeakHashMap;

/** Main-thread only; critical survival and existing executor steps are never throttled. */
public final class NpcLodBudgets {
    private static final Map<MinecraftServer, FairTickBudget> PLANNING = new WeakHashMap<>();
    private static final Map<MinecraftServer, TickByteBudget> CONTEXT_BYTES = new WeakHashMap<>();
    private NpcLodBudgets() {}
    public static FairTickBudget planning(MinecraftServer server) {
        return PLANNING.computeIfAbsent(server, s -> new FairTickBudget(8, 2_000_000, 8192));
    }
    public static TickByteBudget contextBytes(MinecraftServer server) {
        return CONTEXT_BYTES.computeIfAbsent(server,s->new TickByteBudget(65_536,524_288));
    }
}
