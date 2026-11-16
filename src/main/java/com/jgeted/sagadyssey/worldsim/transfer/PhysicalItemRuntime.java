package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 新共享消费者显式启用；普通世界无此日志时不建数据、不起线程。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class PhysicalItemRuntime {
    public static final String FILE_NAME = "sagadyssey_item_operations.sgw";
    private record State(PhysicalItemService service, boolean blocked) {}
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private PhysicalItemRuntime() {}
    public static Optional<PhysicalItemService> existing(MinecraftServer server) { return Optional.ofNullable(state(server).service()); }
    public static PhysicalItemService activate(MinecraftServer server) throws IOException {
        var state = state(server); if (state.blocked()) throw new IOException("physical item log requires recovery");
        if (state.service() != null) return state.service();
        var service = open(server); STATES.put(server, new State(service, false)); return service;
    }
    private static PhysicalItemService open(MinecraftServer server) throws IOException {
        return new PhysicalItemService(path(server), RightsStateData.get(server), TransferLedgerData.get(server));
    }
    private static State state(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("item runtime requires server thread");
        var cached = STATES.get(server); if (cached != null) return cached; State loaded;
        try {
            boolean present = false; Path file = path(server);
            for (String suffix : List.of("", ".old", "_old")) try {
                Files.readAttributes(file.resolveSibling(file.getFileName() + suffix), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); present = true;
            } catch (NoSuchFileException absent) { /* Only confirmed absence is safe. */ }
            loaded = new State(present ? open(server) : null, false);
        } catch (IOException | RuntimeException exception) {
            loaded = new State(null, true); LoggerFactory.getLogger(PhysicalItemRuntime.class).error("共享实物操作日志需要核对，保留原文件", exception);
        }
        STATES.put(server, loaded); return loaded;
    }
    private static Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { var state = STATES.remove(event.getServer()); if (state != null && state.service() != null) state.service().close(); }
}
