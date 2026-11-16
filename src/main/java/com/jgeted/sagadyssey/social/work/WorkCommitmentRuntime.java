package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
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

/** 通用劳动接点。无日志的普通世界不建档/线程；损坏日志保留并阻止新派工。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class WorkCommitmentRuntime {
    public static final String FILE_NAME = "sagadyssey_work_commitments.sgw";
    private record State(WorkCommitmentService service, boolean blocked) {}
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private record Intake(UUID operation, long expiresAt) {}
    private static final Map<MinecraftServer, Map<UUID, Intake>> INTAKES = new WeakHashMap<>();
    private WorkCommitmentRuntime() {}
    public static boolean blocksCivil(NpcBase npc) {
        return !npc.level().isClientSide() && npc.getServer() != null && blocksCivil(npc.getServer(), npc.getCharacterId());
    }
    public static boolean blocksCivil(MinecraftServer server, UUID person) {
        return blocksOther(server,person,null);
    }
    /** Handoff inspection may ignore only its own operation. Other holders, latches and unreadable logs still block. */
    public static boolean blocksOther(MinecraftServer server,UUID person,UUID operation){
        var state=state(server);if(state.blocked())return true;
        var holder=state.service()==null?Optional.<WorkCommitment>empty():state.service().holder(person);
        var intake=intake(server,person);return holder.filter(w->!w.intent().id().equals(operation)).isPresent()||intake!=null&&!intake.operation().equals(operation);
    }
    /** Short handoff latch: prevents civil replanning while a verified claim reaches its durable log. Never grants work authority. */
    public static boolean reserveIntake(MinecraftServer server, UUID person, UUID operation, long expiresAt) {
        var state=state(server);long now=server.overworld().getGameTime();
        if(expiresAt<=now || expiresAt>now+100 || state.blocked())return false;
        var held=state.service()==null?Optional.<WorkCommitment>empty():state.service().holder(person);
        if(held.isPresent())return held.get().intent().id().equals(operation);
        var old=intake(server,person);if(old!=null&&!old.operation().equals(operation))return false;
        var map=INTAKES.computeIfAbsent(server,ignored->new HashMap<>());
        if(map.size()>=1024&&!map.containsKey(person)){map.entrySet().removeIf(e->e.getValue().expiresAt()<=now);if(map.size()>=1024)return false;}
        map.put(person,new Intake(operation,expiresAt));return true;
    }
    public static void releaseIntake(MinecraftServer server,UUID person,UUID operation){
        if(!server.isSameThread())throw new IllegalStateException("intake requires server thread");var map=INTAKES.get(server);if(map!=null)map.computeIfPresent(person,(id,old)->old.operation().equals(operation)?null:old);
    }
    private static Intake intake(MinecraftServer server,UUID person){
        var map=INTAKES.get(server);if(map==null)return null;var value=map.get(person);
        if(value!=null&&value.expiresAt()<=server.overworld().getGameTime()){map.remove(person);return null;}return value;
    }
    public static Optional<WorkCommitmentService> existing(MinecraftServer server) { return Optional.ofNullable(state(server).service()); }
    public static WorkCommitmentService activate(MinecraftServer server) throws IOException {
        var state = state(server); if (state.blocked()) throw new IOException("shared work log requires recovery");
        if (state.service() != null) return state.service();
        var service = new WorkCommitmentService(path(server)); STATES.put(server, new State(service, false)); return service;
    }
    private static State state(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("work runtime requires server thread");
        State cached = STATES.get(server); if (cached != null) return cached;
        State loaded;
        try {
            boolean present = false; Path file = path(server);
            for (String suffix : List.of("", ".old", "_old")) try {
                Files.readAttributes(file.resolveSibling(file.getFileName() + suffix), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); present = true;
            } catch (NoSuchFileException absent) { /* Only proven absence is safe. */ }
            loaded = new State(present ? new WorkCommitmentService(file) : null, false);
        } catch (IOException | RuntimeException exception) {
            loaded = new State(null, true); LoggerFactory.getLogger(WorkCommitmentRuntime.class).error("共享劳动日志需要核对，保留原文件并暂停新派工", exception);
        }
        STATES.put(server, loaded); return loaded;
    }
    private static Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { INTAKES.remove(event.getServer());var state = STATES.remove(event.getServer()); if (state != null && state.service() != null) state.service().close(); }
}
