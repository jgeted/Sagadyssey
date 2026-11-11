package com.jgeted.sagadyssey.worldsim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Tracks the owners of temporary tickets now that native tickets no longer carry an owner key. */
public final class ChunkTickets {
    private static final DeferredRegister<TicketType> TYPES = DeferredRegister.create(Registries.TICKET_TYPE, "sagadyssey");
    private static final Map<String, Kind> KINDS = new HashMap<>();
    private static final Map<ServerChunkCache, Map<Lease, Set<Object>>> OWNERS = new WeakHashMap<>();

    static {
        define("development-observer", 0);
        define("growth-observer", 0);
        define("industry-ab-native", 0);
        define("industry-completion-native", 0);
        define("known-sheep-component", 0);
        define("pickaxe-batch", 0);
        define("sagadyssey-household-caravan", 60);
        define("sagadyssey-industry-forest", 0);
        define("sagadyssey-local-dispute", 60);
        define("sagadyssey-regional-courier", 60);
        define("sagadyssey-self-supply-test", 0);
        define("single-development-observer", 0);
        define("society-r1-crops", 0);
    }

    public record Kind(DeferredHolder<TicketType, TicketType> type) {}
    private record Lease(Kind kind, ChunkPos chunk, int radius) {}

    private ChunkTickets() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }

    private static void define(String name, int ticks) {
        int flags = TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE;
        KINDS.put(name, new Kind(TYPES.register(name, () -> new TicketType(ticks, flags))));
    }

    public static Kind kind(String name) {
        Kind kind = KINDS.get(name);
        if (kind == null) throw new IllegalArgumentException("Unregistered chunk ticket " + name);
        return kind;
    }

    public static void add(ServerChunkCache chunks, Kind kind, ChunkPos position, int radius, Object owner, boolean tick) {
        if (!tick) throw new IllegalArgumentException("Expected a simulation ticket");
        OWNERS.computeIfAbsent(chunks, ignored -> new HashMap<>())
                .computeIfAbsent(new Lease(kind, position, radius), ignored -> new HashSet<>()).add(owner);
        chunks.addTicketWithRadius(kind.type.get(), position, radius);
    }

    public static void remove(ServerChunkCache chunks, Kind kind, ChunkPos position, int radius, Object owner, boolean tick) {
        if (!tick) throw new IllegalArgumentException("Expected a simulation ticket");
        Map<Lease, Set<Object>> leases = OWNERS.get(chunks);
        if (leases == null) return;
        Lease lease = new Lease(kind, position, radius);
        Set<Object> owners = leases.get(lease);
        if (owners != null && owners.remove(owner) && owners.isEmpty()) {
            leases.remove(lease);
            chunks.removeTicketWithRadius(kind.type.get(), position, radius);
        }
    }
}
