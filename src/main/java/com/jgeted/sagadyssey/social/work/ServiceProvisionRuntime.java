package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.PhysicalItemRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 独立共享调度；即使军事受理暂停，既有服役费用和退役欠款仍继续推进。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class ServiceProvisionRuntime {
    public static final String FILE_NAME = "sagadyssey_service_provision.sgw";
    private record State(ServiceProvisionService service, boolean blocked) {}
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private ServiceProvisionRuntime() {}
    public static Optional<ServiceProvisionService> existing(MinecraftServer server) { return Optional.ofNullable(state(server).service()); }
    public static boolean ownsNeeds(NpcBase npc) { return !npc.level().isClientSide() && npc.getServer() != null && ownsNeeds(npc.getServer(), npc.getCharacterId()); }
    public static boolean ownsNeeds(MinecraftServer server, UUID person) { var state = state(server); return state.service() != null && state.service().ownsNeeds(person); }
    public static ServiceProvisionService activate(MinecraftServer server) throws IOException {
        var state = state(server); if (state.blocked()) throw new IOException("service provision requires recovery");
        if (state.service() != null) return state.service(); var service = open(server); STATES.put(server, new State(service, false)); return service;
    }
    private static ServiceProvisionService open(MinecraftServer server) throws IOException {
        var characters = CharacterRegistryData.get(server);
        return new ServiceProvisionService(path(server), PhysicalItemRuntime.activate(server), RightsStateData.get(server), NpcLifeData.get(server),
                location -> location.resolve(server, characters), terms -> terms.bag().body(server, characters)
                .map(npc -> (npc.getTarget() != null ? 2 : 0) + (npc.getNavigation().isInProgress() ? 1 : 0)).orElse(0));
    }
    private static State state(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("service provision requires server thread");
        var known = STATES.get(server); if (known != null) return known;
        State state;
        try {
            boolean found = false; var path = path(server);
            for (var suffix : List.of("", ".old", "_old")) try { Files.readAttributes(path.resolveSibling(path.getFileName() + suffix), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); found = true; }
            catch (NoSuchFileException absent) { /* Confirmed absence only. */ }
            state = new State(found ? open(server) : null, false);
        } catch (IOException | RuntimeException exception) {
            state = new State(null, true); org.slf4j.LoggerFactory.getLogger(ServiceProvisionRuntime.class).error("共享粮饷协议需要恢复，保留原档", exception);
        } STATES.put(server, state); return state;
    }
    private static Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME); }
    @SubscribeEvent public static void started(ServerStartedEvent event) { state(event.getServer()); }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { var state = STATES.get(event.getServer()); if (state != null && state.service() != null) state.service().tick(event.getServer().overworld().getGameTime()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { var state = STATES.remove(event.getServer()); if (state != null && state.service() != null) state.service().close(); }
}
