package com.jgeted.sagadyssey.social.war.runtime;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 世界作用域的纯Java会话：归档成功才换代；异步完成不能直接修改主线程状态。 */
public final class WarSession implements AutoCloseable {
    public enum Phase { ACTIVE, ARCHIVING, PAUSED, CLOSED }
    private final Thread owner = Thread.currentThread();
    private final WarDefinitions definitions;
    private final WarCommandService.Authority authority;
    private final WarCommandService.Limits limits;
    private final Function<WarState, CompletableFuture<WarArchiveRef>> archiveWriter;
    private final Runnable changed;
    private WarCommandService service;
    private Phase phase;
    private CompletableFuture<WarArchiveRef> pending;
    private WarState archiving;
    private String problem = "";
    private boolean autoArchiveEligible = true;

    public WarSession(WarDefinitions definitions, WarCommandService.Authority authority, WarCommandService.Limits limits,
                      WarState state, boolean active, Function<WarState, CompletableFuture<WarArchiveRef>> archiveWriter,
                      Runnable changed) {
        this.definitions = Objects.requireNonNull(definitions); this.authority = Objects.requireNonNull(authority);
        this.limits = Objects.requireNonNull(limits); this.archiveWriter = Objects.requireNonNull(archiveWriter);
        this.changed = Objects.requireNonNull(changed);
        service = new WarCommandService(definitions, authority, limits, state);
        phase = active ? Phase.ACTIVE : Phase.PAUSED;
    }

    public Phase phase() { checkThread(); return phase; }
    public String problem() { checkThread(); return problem; }
    public long generation() { checkThread(); return service.generation(); }
    public WarState checkpoint() { checkThread(); return service.checkpoint(); }
    public Optional<WarUnit> unit(UUID id) { checkThread(); return service.unit(id); }
    public List<WarUnit> units() { checkThread(); return service.units(); }

    public WarResult submit(WarCommand command, long now) {
        checkThread();
        if (phase != Phase.ACTIVE) return unavailable();
        WarResult result = service.submit(command, now);
        if (result.status() == WarResult.Status.APPLIED) { autoArchiveEligible = true; changed.run(); }
        return result;
    }

    public WarResult deliver(UUID unit, long generation, UUID order, long now) {
        checkThread();
        if (phase != Phase.ACTIVE) return unavailable();
        WarResult result = service.deliver(unit, generation, order, now);
        if (result.status() == WarResult.Status.APPLIED) { autoArchiveEligible = true; changed.run(); }
        return result;
    }

    /** 显式维护入口；不会在客户端重试时偷偷改写请求的代次。 */
    public boolean archive() {
        checkThread();
        if (phase != Phase.ACTIVE || service.generation() == Long.MAX_VALUE) return false;
        autoArchiveEligible = false;
        WarState snapshot = service.checkpoint();
        if (snapshot.archivableOperationCount() == 0) return false;
        try {
            pending = Objects.requireNonNull(archiveWriter.apply(snapshot));
            archiving = snapshot; phase = Phase.ARCHIVING; problem = "";
            return true;
        } catch (RuntimeException exception) {
            problem = "ARCHIVE_START_FAILED:" + exception.getClass().getSimpleName(); return false;
        }
    }

    public void tick() {
        checkThread();
        int highWaterMark = limits.maxOperations() - limits.maxOperations() / 4;
        if (phase == Phase.ACTIVE && autoArchiveEligible && problem.isEmpty() && service.operationCount() >= highWaterMark) archive();
        if (phase != Phase.ARCHIVING || !pending.isDone()) return;
        try {
            WarState compact = archiving.compact(pending.join());
            var replacement = new WarCommandService(definitions, authority, limits, compact);
            changed.run(); service = replacement; phase = Phase.ACTIVE;
        } catch (RuntimeException exception) {
            // The full old service remains authoritative unless construction and publication both succeeded.
            problem = "ARCHIVE_FAILED:" + exception.getClass().getSimpleName(); phase = Phase.ACTIVE;
        } finally { pending = null; archiving = null; }
    }

    public void pause() {
        checkThread();
        if (phase == Phase.CLOSED) throw new IllegalStateException("session closed");
        pending = null; archiving = null; phase = Phase.PAUSED;
    }
    public void resume() {
        checkThread();
        if (phase != Phase.PAUSED) throw new IllegalStateException("session is not paused");
        phase = Phase.ACTIVE; autoArchiveEligible = true;
    }
    @Override public void close() { checkThread(); if (phase != Phase.CLOSED) { pause(); phase = Phase.CLOSED; } }
    private WarResult unavailable() {
        return WarResult.rejected(phase == Phase.ARCHIVING ? WarResult.Status.ARCHIVING : WarResult.Status.UNAVAILABLE);
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("war session owner thread required");
    }
}
