package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** 没有战争档的普通世界只做启动文件检查；tick不扫描世界、不创建数据或后台线程。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarServerEvents {
    private static final Logger LOG = LoggerFactory.getLogger(WarServerEvents.class);
    private static final Map<MinecraftServer, WarServerRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private WarServerEvents() {}

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        var codec = new WarStateCodec(WarStateCodec.Limits.defaults());
        readExisting(server.getWorldPath(LevelResource.ROOT).resolve("data"), codec).ifPresent(data -> install(server, data, codec));
    }
    /** 跨军事存档恢复检查：服役意图还在而兵团主档丢失时，不新建一份空军队覆盖历史。 */
    public static Optional<WarSavedData> readExisting(Path directory, WarStateCodec codec) {
        var core = WarSavedData.readDisk(directory, codec); if (core.isPresent()) return core;
        try {
            for (String file : List.of(WarMobilizationRuntime.FILE_NAME, WarDispatchJournal.FILE_NAME, WarDispatchJournal.ARCHIVE_DIRECTORY,
                    WarCohesionJournal.FILE_NAME,WarCommandPostJournal.FILE_NAME,WarCavalryJournal.FILE_NAME,WarFarRuntime.FILE)) for (String suffix : List.of("", ".old", "_old")) {
                try {
                    Files.readAttributes(directory.resolve(file + suffix), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    var blocked = WarSavedData.create(codec); blocked.block(file.equals(WarDispatchJournal.FILE_NAME) ? "DISPATCH_WITHOUT_CORE_STATE_REQUIRES_REVIEW" : file.equals(WarCohesionJournal.FILE_NAME) ? "TACTICS_WITHOUT_CORE_STATE_REQUIRES_REVIEW" : file.equals(WarCommandPostJournal.FILE_NAME)?"COMMAND_POSTS_WITHOUT_CORE_STATE_REQUIRES_REVIEW":file.equals(WarCavalryJournal.FILE_NAME)?"CAVALRY_WITHOUT_CORE_STATE_REQUIRES_REVIEW":"MOBILIZATION_WITHOUT_CORE_STATE_REQUIRES_REVIEW"); return Optional.of(blocked);
                } catch (NoSuchFileException absent) { /* Only confirmed absence allows checking the next file. */ }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            var blocked = WarSavedData.create(codec); blocked.block("MOBILIZATION_FILE_ACCESS_REQUIRES_REVIEW"); return Optional.of(blocked);
        }
    }
    private static WarServerRuntime install(MinecraftServer server, WarSavedData data, WarStateCodec codec) {
        if (!server.isSameThread()) throw new IllegalStateException("war installation requires server thread");
        if (RUNTIMES.containsKey(server)) throw new IllegalStateException("war runtime already installed");
        var runtime = new WarServerRuntime(server, data, codec);
        WarClothingProjection.bind(server, runtime);
        // The native set operation marks data dirty; an opaque blocked ledger must stay out
        // of that write path while the runtime still exposes its recovery diagnosis.
        if (data.writable()) server.overworld().getDataStorage().set(WarSavedData.FACTORY.type(WarSavedData.NAME), data);
        RUNTIMES.put(server, runtime);
        if (!data.writable()) LOG.error("战争模块只读封锁，保留原文件：{}", data.blockedReason());
        return runtime;
    }
    public static Optional<WarServerRuntime> existing(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("war lookup requires server thread");
        return Optional.ofNullable(RUNTIMES.get(server));
    }
    public static WarMobilizationService.Preview previewMilitia(MinecraftServer server, WarMilitiaRequest request) {
        var runtime = existing(server);
        if (runtime.isEmpty()) return new WarMobilizationService.Preview(WarMobilizationRuntime.sharedMissing(), List.of("WAR_NOT_ACTIVATED"));
        return runtime.get().mobilization().map(militia -> militia.preview(request)).orElseGet(() ->
                new WarMobilizationService.Preview(WarMobilizationRuntime.sharedMissing(), List.of("WAR_RECOVERY_REQUIRED")));
    }
    /** 仅供可信服务端功能显式启用；不会因一次普通查询创建存档。 */
    public static WarServerRuntime activate(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("war activation requires server thread");
        WarServerRuntime runtime = RUNTIMES.get(server);
        if (runtime == null) {
            var codec = new WarStateCodec(WarStateCodec.Limits.defaults());
            WarSavedData data = readExisting(server.getWorldPath(LevelResource.ROOT).resolve("data"), codec)
                    .orElseGet(() -> WarSavedData.create(codec));
            runtime = install(server, data, codec);
        }
        runtime.activate(); return runtime;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        WarServerRuntime runtime = RUNTIMES.get(event.getServer()); if (runtime != null) runtime.tick();
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        WarServerRuntime runtime = RUNTIMES.get(event.getServer()); if (runtime != null) runtime.stopping();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        WarServerRuntime runtime = RUNTIMES.remove(event.getServer()); if (runtime != null) runtime.close();
    }
}
