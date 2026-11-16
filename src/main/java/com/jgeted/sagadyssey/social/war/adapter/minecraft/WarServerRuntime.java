package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.WarState;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.runtime.WarSession;
import com.jgeted.sagadyssey.social.war.runtime.WarTickTelemetry;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.*;

/** 单个服务器的装配边界；不提供客户端命令入口，不读取或扫描NPC。 */
public final class WarServerRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final WarSavedData data;
    private final WarArchiveStore archives;
    private final WarTickTelemetry telemetry = new WarTickTelemetry();
    private WarSession session;
    private WarMobilizationRuntime mobilization;
    private WarNativeMobilization nativeMobilization;
    private WarFieldRuntime field;
    private WarCommandMediaRuntime media;
    private String mediaProblem="";
    private WarCommandService.Authority authority = (actor, army) -> WarCommandService.Permission.UNAVAILABLE;

    public WarServerRuntime(MinecraftServer server, WarSavedData data, WarStateCodec codec) {
        this.server = Objects.requireNonNull(server); this.data = Objects.requireNonNull(data);
        requireServerThread();
        archives = new WarArchiveStore(server.getWorldPath(LevelResource.ROOT).resolve("data/sagadyssey_war_archives"), codec);
        if (!data.writable()) return;
        try {
            WarState state = data.checkpoint(); archives.verify(state);
            session = new WarSession(InitialWarDefinitions.create(), (actor, army) -> authority.check(actor, army),
                    WarCommandService.Limits.defaults(), state, data.enabled(), archives::write, data::setDirty);
            data.attach(session);
            mobilization = new WarMobilizationRuntime(server, id -> session.unit(id),
                    (actor, army) -> authority.check(actor, army), () -> session.phase() == WarSession.Phase.ACTIVE);
        } catch (IOException | IllegalArgumentException exception) {
            data.block("RECOVERY_REQUIRED:" + exception.getClass().getSimpleName());
        }
    }

    /** 正式授权适配尚未接入；可信服务端集成必须显式绑定，启动默认明确不可用。 */
    public void bindAuthority(WarCommandService.Authority authority) {
        requireServerThread(); this.authority = Objects.requireNonNull(authority);
    }
    public WarCommandService.Permission permission(UUID actor,UUID army){requireServerThread();return authority.check(actor,army);}
    public boolean servicePermits(WarMilitiaRequest request,long now){requireServerThread();return nativeMobilization!=null&&nativeMobilization.permits(request,now);}
    public Optional<WarNativeMobilization> nativeMobilization(){requireServerThread();return Optional.ofNullable(nativeMobilization);}
    public Optional<WarCommandMediaRuntime> commandMedia(){requireServerThread();return Optional.ofNullable(media);}
    /** Explicit UI assembly for existing armies. Queries alone do not create world military state. */
    public Optional<WarCommandMediaRuntime> openCommandMedia(){
        requireServerThread();if(media!=null)return Optional.of(media);if(session==null || !mediaProblem.isEmpty())return Optional.empty();
        WarCommandPostJournal journal=null;
        try{journal=new WarCommandPostJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/"+WarCommandPostJournal.FILE_NAME));media=new WarCommandMediaRuntime(server,this,journal);return Optional.of(media);}
        catch(IOException|RuntimeException e){if(journal!=null)journal.close();mediaProblem="COMMAND_POST_RECOVERY_REQUIRED:"+e.getClass().getSimpleName();return Optional.empty();}
    }
    public String commandMediaProblem(){requireServerThread();return mediaProblem;}
    public WarResult submit(WarCommand command) {
        requireServerThread();
        return session == null ? WarResult.rejected(WarResult.Status.UNAVAILABLE)
                : session.submit(command, server.overworld().getGameTime());
    }
    public WarResult deliver(UUID unit, long generation, UUID order) {
        requireServerThread();
        return session == null ? WarResult.rejected(WarResult.Status.UNAVAILABLE)
                : session.deliver(unit, generation, order, server.overworld().getGameTime());
    }
    public WarSavedData data() { requireServerThread(); return data; }
    public Optional<WarSession> session() { requireServerThread(); return Optional.ofNullable(session); }
    /** 共享端完成契约适配后显式绑定；没有默认成功实现。 */
    public Optional<WarMobilizationRuntime> mobilization() { requireServerThread(); return Optional.ofNullable(mobilization); }
    public WarNativeMobilization bindNativeMobilization(WarCandidateInspector.Sources sources) throws IOException {
        requireServerThread(); if (mobilization == null || nativeMobilization != null) throw new IllegalStateException("native military binding unavailable/already installed");
        nativeMobilization = WarNativeMobilization.bind(server, mobilization, sources, (actor, army) -> authority.check(actor, army)); return nativeMobilization;
    }
    public WarFieldRuntime bindFieldExecution(WarFieldRuntime.Sources sources) {
        return bindFieldExecution(sources, WarNativeBindings.currentBuild(), () -> new WarDispatchJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/" + WarDispatchJournal.FILE_NAME)));
    }
    public WarFieldRuntime bindFieldExecution(WarFieldRuntime.Sources sources, WarNativeBindings bindings, WarDispatchPorts.JournalFactory journals) {
        return bindFieldExecution(sources,bindings,journals,
                () -> new WarCohesionJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/" + WarCohesionJournal.FILE_NAME)),
                new com.jgeted.sagadyssey.social.war.application.WarCohesionPolicy(com.jgeted.sagadyssey.social.war.application.WarCohesionPolicy.Rules.prototype()));
    }
    public WarFieldRuntime bindFieldExecution(WarFieldRuntime.Sources sources, WarNativeBindings bindings, WarDispatchPorts.JournalFactory journals,
            com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.JournalFactory tacticalJournals,
            com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Policy tacticalPolicy) {
        return bindFieldExecution(sources,bindings,journals,tacticalJournals,tacticalPolicy,
                ()->new WarCavalryJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/"+WarCavalryJournal.FILE_NAME)),
                new com.jgeted.sagadyssey.social.war.application.WarCavalryPolicy(com.jgeted.sagadyssey.social.war.application.WarCavalryPolicy.Rules.prototype()));
    }
    public WarFieldRuntime bindFieldExecution(WarFieldRuntime.Sources sources,WarNativeBindings bindings,WarDispatchPorts.JournalFactory journals,
            com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.JournalFactory tacticalJournals,com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Policy tacticalPolicy,
            com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.JournalFactory cavalryJournals,com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Policy cavalryPolicy){
        requireServerThread(); if (nativeMobilization == null || field != null) throw new IllegalStateException("native militia must be bound before field execution");
        field = new WarFieldRuntime(server, this, mobilization, sources, (actor, army) -> authority.check(actor, army), bindings, journals,tacticalJournals,tacticalPolicy,cavalryJournals,cavalryPolicy); return field;
    }
    public Optional<WarFieldRuntime> field() { requireServerThread(); return Optional.ofNullable(field); }
    public void activate() {
        requireServerThread();
        if (session == null) throw new IllegalStateException(data.blockedReason());
        if (session.phase() == WarSession.Phase.PAUSED) session.resume();
        data.enabled(true);
    }
    /** 暂停受理，保留当前令、待送达令和记录；不代表结清未来粮饷等义务。 */
    public void pause() {
        requireServerThread();
        if (session != null) { session.pause(); data.enabled(false); }
    }
    public WarTickTelemetry telemetry() { requireServerThread(); return telemetry; }
    public void tick() {
        requireServerThread();
        long started = telemetry.start();
        try {
            if (session != null) session.tick();
            if (mobilization != null) mobilization.tick();
            if (field != null) field.tick();
            if (media != null) media.tick();
        } finally {
            if (started != 0) telemetry.coordinator(server.overworld().getGameTime(), started);
        }
    }
    public void stopping() { requireServerThread(); if (session != null) session.pause(); if (mobilization != null) mobilization.stopping(); }
    @Override public void close() {
        requireServerThread(); if (session != null) session.close(); archives.close();
        if (field != null) field.close();
        if(media!=null)media.close();
        if (mobilization != null) mobilization.close();
        if (nativeMobilization != null) nativeMobilization.close();
        authority = (actor, army) -> WarCommandService.Permission.UNAVAILABLE;
    }
    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("war runtime requires server thread");
    }
}
