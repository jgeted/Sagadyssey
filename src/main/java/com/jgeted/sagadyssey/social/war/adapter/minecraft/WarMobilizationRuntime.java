package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** 军事服役独立装配。每刻在有限时间内轮转已持久化意图步骤。 */
public final class WarMobilizationRuntime implements AutoCloseable {
    public static final String FILE_NAME = "sagadyssey_war_mobilization.dat";
    private record UnitIdentity(UUID army, WarKey loadout, int definitionVersion) {}
    private final MinecraftServer server;
    private final Function<UUID, Optional<WarUnit>> units;
    private final BiFunction<UUID, UUID, WarCommandService.Permission> authority;
    private final BooleanSupplier accepting;
    private final Map<UUID, UnitIdentity> verifiedUnits = new HashMap<>();
    private final LinkedHashSet<UUID> ready = new LinkedHashSet<>();
    private final Map<UUID, WarMobilization> activePeople = new HashMap<>();
    private final Map<UUID,NavigableMap<UUID,WarMobilization>> activeUnits=new HashMap<>();
    private final Map<UUID,String> progress=new HashMap<>();
    public String progress(UUID id){checkThread();return progress.getOrDefault(id,"");}
    private WarMobilizationService service;
    private WarMobilizationJournal journal;
    private WarCandidateInspector candidates;
    private boolean stopping;
    private long lastTick = -1;

    public WarMobilizationRuntime(MinecraftServer server, Function<UUID, Optional<WarUnit>> units,
                                  BiFunction<UUID, UUID, WarCommandService.Permission> authority, BooleanSupplier accepting) {
        this.server = Objects.requireNonNull(server); this.units = Objects.requireNonNull(units);
        this.authority = Objects.requireNonNull(authority); this.accepting = Objects.requireNonNull(accepting);
    }
    public static Set<WarKey> sharedMissing() {
        Set<WarKey> missing = new TreeSet<>(Bindings.unavailable().missing()); missing.remove(WarMobilizationPorts.JOURNAL);
        return Set.copyOf(missing);
    }
    /** 原生只读来源由共享维护流程显式提供；不会因预览而创建人口或生活领域数据。 */
    public void bindCandidateInspector(WarCandidateInspector inspector) {
        checkThread();
        if (stopping || candidates != null || !Objects.requireNonNull(inspector).belongsTo(server)) throw new IllegalStateException("candidate inspector binding mismatch");
        candidates = inspector;
    }
    public WarPreparation prepare(List<WarMilitiaRequest> requests, long forecastTicks) {
        checkThread(); long now = server.overworld().getGameTime();
        var preparation = new WarPreparationService(InitialWarDefinitions.create(), InitialWarDefinitions.LOADOUTS,
                (actor, army) -> authority.apply(actor, army), (batch, observedAt) -> {
            Map<UUID, WarCandidateReport> reports = new HashMap<>(); List<WarMilitiaRequest> nativeBatch = new ArrayList<>();
            for (var request : batch) {
                var unit = units.apply(request.unitId());
                if (stopping || !accepting.getAsBoolean()) reports.put(request.id(), WarCandidateReport.unavailable(request, observedAt, "WAR_INTAKE_PAUSED"));
                else if (unit.isEmpty() || !matches(unit.get(), request)) reports.put(request.id(), WarCandidateReport.unavailable(request, observedAt, "ARMY_OR_LOADOUT_DOES_NOT_MATCH_UNIT"));
                else if (candidates == null) reports.put(request.id(), WarCandidateReport.unavailable(request, observedAt, "NATIVE_INSPECTION_NOT_BOUND"));
                else if (service != null && !preview(request).ready()) reports.put(request.id(), WarCandidateReport.unavailable(request, observedAt, "SHARED_PREVIEW_REQUIRES_REVIEW"));
                else nativeBatch.add(request);
            }
            if (!nativeBatch.isEmpty()) for (var report : candidates.inspect(nativeBatch, observedAt)) reports.put(report.requestId(), report);
            return batch.stream().map(request -> reports.get(request.id())).toList();
        }, () -> service == null ? sharedMissing() : Set.of());
        return preparation.prepare(requests, now, forecastTicks);
    }
    public void bind(Inspection inspection, Map<WarMobilization.Stage, EffectPort> effects) throws IOException {
        checkThread();
        if (service != null || stopping) throw new IllegalStateException("militia runtime already bound or stopping");
        if (inspection.protocolVersion() != 1) throw new IllegalArgumentException("unsupported inspection protocol");
        for (var stage : WarMobilization.Stage.values()) if (stage.effect()
                && (effects.get(stage) == null || effects.get(stage).protocolVersion() != 1)) {
            throw new IllegalArgumentException("missing shared militia capability: " + stage.capability());
        }
        var opened = new WarMobilizationJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME),
                new WarMobilizationCodec(16 * 1024 * 1024, WarMobilizationService.RECORD_LIMIT));
        try {
            for (var record : opened.recover()) if (!durableUnit(record.request())) {
                throw new IOException("militia record references a missing or inconsistent durable unit; preserve journal");
            }
            var bound = new WarMobilizationService(InitialWarDefinitions.create(), InitialWarDefinitions.LOADOUTS,
                    new Bindings(Optional.of(inspection), Optional.of(opened), effects), opened.capacity());
            var pending = bound.records().stream().filter(record -> record.stage() != WarMobilization.Stage.RELEASED)
                    .map(record -> record.request().id()).toList();
            service = bound; journal = opened; ready.addAll(pending);
            for (var record : bound.records()) if (record.stage() == WarMobilization.Stage.ACTIVE && !record.demobilizeRequested() && record.reviewReason().isEmpty()) indexActive(record);
        } catch (IOException | RuntimeException exception) { opened.close(); throw exception; }
    }
    public WarMobilizationService.Preview preview(WarMilitiaRequest request) {
        checkThread(); List<String> reasons = new ArrayList<>();
        if (stopping || !accepting.getAsBoolean()) reasons.add("WAR_INTAKE_PAUSED");
        if (!com.jgeted.sagadyssey.social.war.domain.WarServiceRoles.supported(request)) reasons.add("SERVICE_CATEGORY_NOT_ENABLED_IN_M2A");
        if (authority.apply(request.actorId(), request.armyId()) != WarCommandService.Permission.ALLOWED) reasons.add("ARMY_AUTHORITY_UNAVAILABLE_OR_DENIED");
        var unit = units.apply(request.unitId());
        if (unit.isEmpty() || !matches(unit.get(), request)) reasons.add("ARMY_OR_LOADOUT_DOES_NOT_MATCH_UNIT");
        if (service == null) return new WarMobilizationService.Preview(sharedMissing(), reasons);
        if (!reasons.isEmpty()) return new WarMobilizationService.Preview(Set.of(), reasons);
        if (!durableUnit(request)) return new WarMobilizationService.Preview(Set.of(), List.of("UNIT_NOT_YET_DURABLE_OR_REQUIRES_REVIEW"));
        var preview = service.preview(request, server.overworld().getGameTime()); reasons.addAll(preview.reasons());
        return new WarMobilizationService.Preview(preview.missing(), reasons);
    }
    public WarMobilizationService.Result begin(WarMilitiaRequest request) {
        checkThread();
        if (service != null && service.find(request.id()).isPresent()) {
            if (authority.apply(request.actorId(), request.armyId()) != WarCommandService.Permission.ALLOWED) return blocked("ARMY_AUTHORITY_DENIED");
            return service.begin(request, server.overworld().getGameTime());
        }
        var preview = preview(request);
        if (!preview.ready()) return new WarMobilizationService.Result(WarMobilizationService.Outcome.BLOCKED,
                Optional.empty(), "MISSING:" + preview.missing() + ";" + preview.reasons());
        var result = service.begin(request, server.overworld().getGameTime());
        if (result.outcome() == WarMobilizationService.Outcome.ACCEPTED) ready.add(request.id());
        return result;
    }
    public WarMobilizationService.Result demobilize(UUID id, UUID actor) {
        checkThread();
        if (service == null || stopping) return blocked("MILITIA_NOT_BOUND_OR_STOPPING");
        var record = service.find(id);
        if (record.isEmpty()) return new WarMobilizationService.Result(WarMobilizationService.Outcome.NOT_FOUND, Optional.empty(), "");
        if (authority.apply(actor, record.get().request().armyId()) != WarCommandService.Permission.ALLOWED) return blocked("ARMY_AUTHORITY_DENIED");
        var result = service.demobilize(id, server.overworld().getGameTime()); removeActive(record.get().request().characterId()); ready.add(id); return result;
    }
    /** Trusted lifecycle/consent adapter only. A deceased lord or expired agreement must not prevent release of original labor. No client endpoint. */
    public WarMobilizationService.Result withdraw(UUID id){
        checkThread();if(service==null||stopping)return blocked("MILITIA_NOT_BOUND_OR_STOPPING");
        var record=service.find(id);if(record.isEmpty())return new WarMobilizationService.Result(WarMobilizationService.Outcome.NOT_FOUND,Optional.empty(),"");
        var result=service.demobilize(id,server.overworld().getGameTime());removeActive(record.get().request().characterId());ready.add(id);return result;
    }
    public void reconcile() {
        checkThread(); if (service == null || stopping) return;
        service.reconcile();
        service.records().stream().filter(record -> record.stage() != WarMobilization.Stage.RELEASED).forEach(record -> ready.add(record.request().id()));
    }
    public void tick() {
        checkThread(); if (service == null || stopping || ready.isEmpty()) return;
        long now = server.overworld().getGameTime(); if (now <= lastTick) return; lastTick = now;
        int steps = Math.min(4, ready.size());
        long start = System.nanoTime();
        for (int i = 0; i < steps && (i == 0 || System.nanoTime() - start < 2_000_000); i++) advanceOne(now);
    }
    private void advanceOne(long now) {
        var iterator = ready.iterator(); UUID id = iterator.next(); iterator.remove();
        var result = service.advance(id, now);
        progress.put(id,result.outcome()+":"+result.reason());
        if (result.outcome() == WarMobilizationService.Outcome.ACTIVE) result.record().filter(record -> !record.demobilizeRequested() && record.reviewReason().isEmpty())
                .ifPresent(record -> indexActive(record));
        result.record().filter(record -> record.stage() != WarMobilization.Stage.ACTIVE || record.demobilizeRequested() || !record.reviewReason().isEmpty())
                .ifPresent(record -> removeActive(record.request().characterId()));
        if (result.outcome() == WarMobilizationService.Outcome.WAITING || result.outcome() == WarMobilizationService.Outcome.PROGRESSED) ready.add(id);
        // REVIEW/BLOCKED保留已落盘的前置意图；显式核对后按原操作号重新排队。
    }
    public Optional<WarMobilizationService> service() { checkThread(); return Optional.ofNullable(service); }
    public Optional<WarMobilization> activePerson(UUID character) {
        checkThread(); return stopping || service == null || !service.recoveryReady() ? Optional.empty() : Optional.ofNullable(activePeople.get(character));
    }
    public List<WarMobilization> activeUnit(UUID unit) {
        checkThread(); if (stopping || service == null || !service.recoveryReady()) return List.of();
        return List.copyOf(activeUnits.getOrDefault(unit,Collections.emptyNavigableMap()).values());
    }
    public void stopping() { checkThread(); stopping = true; }
    @Override public void close() { checkThread(); stopping = true; ready.clear(); verifiedUnits.clear(); activePeople.clear(); activeUnits.clear(); progress.clear(); candidates = null; if (journal != null) journal.close(); }
    private void indexActive(WarMobilization record){removeActive(record.request().characterId());activePeople.put(record.request().characterId(),record);activeUnits.computeIfAbsent(record.request().unitId(),ignored->new TreeMap<>()).put(record.request().characterId(),record);}
    private void removeActive(UUID person){var old=activePeople.remove(person);if(old==null)return;var unit=activeUnits.get(old.request().unitId());if(unit!=null){unit.remove(person);if(unit.isEmpty())activeUnits.remove(old.request().unitId());}}
    private boolean durableUnit(WarMilitiaRequest request) {
        var live = units.apply(request.unitId()); if (live.isEmpty() || !matches(live.get(), request)) return false;
        var identity = new UnitIdentity(live.get().armyId(), live.get().troopType(), live.get().definitionVersion());
        if (identity.equals(verifiedUnits.get(request.unitId()))) return true;
        var disk = WarSavedData.readDisk(server.getWorldPath(LevelResource.ROOT).resolve("data"), new WarStateCodec(WarStateCodec.Limits.defaults()));
        if (disk.isEmpty() || !disk.get().writable()) return false;
        var saved = disk.get().checkpoint().units().get(request.unitId());
        if (saved == null || !saved.armyId().equals(identity.army()) || !saved.troopType().equals(identity.loadout())
                || saved.definitionVersion() != identity.definitionVersion()) return false;
        verifiedUnits.put(request.unitId(), identity); return true;
    }
    private static boolean matches(WarUnit unit, WarMilitiaRequest request) {
        return unit.armyId().equals(request.armyId()) && unit.definitionVersion() == request.definitionVersion()
                && InitialWarDefinitions.LOADOUTS.find(unit.troopType()).isPresent()
                && InitialWarDefinitions.LOADOUTS.find(unit.troopType()).equals(InitialWarDefinitions.LOADOUTS.find(request.loadout()));
    }
    private static WarMobilizationService.Result blocked(String reason) {
        return new WarMobilizationService.Result(WarMobilizationService.Outcome.BLOCKED, Optional.empty(), reason);
    }
    private void checkThread() { if (!server.isSameThread()) throw new IllegalStateException("militia runtime requires server thread"); }
}
