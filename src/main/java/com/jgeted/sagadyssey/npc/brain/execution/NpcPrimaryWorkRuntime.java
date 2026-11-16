package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.work.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** 显式服务端扩展注册。无注册或无共享劳动时不改变原行为；原模式字段从不改写。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class NpcPrimaryWorkRuntime {
    public record Binding(NpcPrimaryWorkControl control, WorkCommitment commitment) {}
    private static final Map<MinecraftServer, Map<String, NpcPrimaryWorkControl>> CONTROLS = new WeakHashMap<>();
    private NpcPrimaryWorkRuntime() {}
    public static void register(MinecraftServer server, String ownerKind, NpcPrimaryWorkControl control) {
        check(server); Objects.requireNonNull(control);
        if (ownerKind == null || ownerKind.isBlank() || CONTROLS.computeIfAbsent(server, ignored -> new HashMap<>()).putIfAbsent(ownerKind, control) != null)
            throw new IllegalStateException("primary work control already registered or invalid");
    }
    public static void unregister(MinecraftServer server, String ownerKind, NpcPrimaryWorkControl control) {
        check(server); var controls = CONTROLS.get(server); if (controls != null) { controls.remove(ownerKind, control); if (controls.isEmpty()) CONTROLS.remove(server); }
    }
    public static Optional<Binding> binding(NpcBase npc) {
        var server = npc.getServer(); if (server == null || npc.level().isClientSide()) return Optional.empty(); check(server);
        var controls = CONTROLS.get(server); if (controls == null) return Optional.empty();
        var holder = WorkCommitmentRuntime.existing(server).flatMap(service -> service.holder(npc.getCharacterId()));
        if (holder.isEmpty()) return Optional.empty(); var control = controls.get(holder.get().intent().ownerKind());
        return control == null ? Optional.empty() : Optional.of(new Binding(control, holder.get()));
    }
    public static boolean reserved(NpcBase npc) { return binding(npc).isPresent(); }
    private static void check(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("primary work control requires server thread"); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { CONTROLS.remove(event.getServer()); }
}
