package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;

import java.util.*;

/** 仅隔离测试使用的显式替身；不注册到正式世界，不作为真实NPC动员/消费/付款证据。 */
final class WarMilitiaTestSupport {
    static WarMilitiaRequest request() {
        return new WarMilitiaRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InitialWarDefinitions.TROOPS.getFirst(), 1, WarMilitiaRequest.Kind.MILITIA, WarMilitiaRequest.Role.COMBATANT,
                new WarMilitiaRequest.Wage(WarKey.parse("minecraft:emerald"), 1, 24_000), 0);
    }
    static final class MemoryJournal implements Journal {
        final Map<UUID, WarMobilization> records = new LinkedHashMap<>();
        boolean waiting, wrongAck;
        @Override public int protocolVersion() { return 1; }
        @Override public int capacity() { return 128; }
        @Override public Collection<WarMobilization> recover() { return List.copyOf(records.values()); }
        @Override public WriteReply persist(WarMobilization record) {
            if (waiting) return new WriteReply(Status.PENDING, Optional.empty(), "test checkpoint pending");
            records.put(record.request().id(), record);
            return new WriteReply(Status.APPLIED, Optional.of(wrongAck ? WarMobilization.start(request()) : record), "");
        }
        @Override public void close() {}
    }
    static final class Backend implements Inspection, EffectPort {
        final Map<UUID, WarMobilization.Receipt> applied = new HashMap<>();
        final Map<WarMobilization.Stage, Effect> effects = new EnumMap<>(WarMobilization.Stage.class);
        final Set<WarMobilization.Stage> pending = EnumSet.noneOf(WarMobilization.Stage.class);
        boolean eligible = true, recoveryReady = true, unknown, wrongReceipt, debt;
        WarMobilization.Stage throwAfter;
        int calls, cancellations, reads;
        @Override public int protocolVersion() { return 1; }
        @Override public Readiness inspect(WarMilitiaRequest request, long now) { return new Readiness(eligible, eligible ? List.of() : List.of("SHARED_AUTHORITY_OR_CHARACTER_NOT_ELIGIBLE")); }
        @Override public Readiness reconcile(Collection<WarMobilization> records) { return new Readiness(recoveryReady, recoveryReady ? List.of() : List.of("ORPHAN_SHARED_OBLIGATION")); }
        @Override public Reply lookup(Effect effect) {
            reads++;
            if (unknown) return Reply.state(Status.UNKNOWN, "test owner cannot prove outcome");
            var receipt = applied.get(effect.operationId());
            if (receipt != null) return Reply.applied(receipt);
            if (pending.contains(effect.stage())) return Reply.state(Status.PENDING, "test delivery in transit");
            return Reply.state(Status.NOT_APPLIED, "test backend owns complete facts and proves no effect");
        }
        @Override public Reply apply(Effect effect) {
            calls++;
            var receipt = new WarMobilization.Receipt(effect.request().id(), effect.request().characterId(), effect.stage(), effect.operationId(),
                    UUID.randomUUID(), effect.now(), debt && effect.stage() == WarMobilization.Stage.CLOSE_PAYROLL);
            applied.put(effect.operationId(), receipt); effects.put(effect.stage(), effect);
            if (throwAfter == effect.stage()) { throwAfter = null; throw new IllegalStateException("test lost acknowledgement after effect"); }
            if (wrongReceipt) return Reply.applied(new WarMobilization.Receipt(receipt.contractId(), UUID.randomUUID(), receipt.stage(),
                    receipt.operationId(), receipt.referenceId(), receipt.recordedAtTick(), false));
            return Reply.applied(receipt);
        }
        @Override public Reply cancel(Effect effect) {
            cancellations++; pending.remove(effect.stage()); return Reply.state(Status.NOT_APPLIED, "test cancellation fully reconciled");
        }
    }
    static Bindings bindings(Backend backend, Journal journal) {
        Map<WarMobilization.Stage, EffectPort> effects = new EnumMap<>(WarMobilization.Stage.class);
        for (var stage : WarMobilization.Stage.values()) if (stage.effect()) effects.put(stage, backend);
        return new Bindings(Optional.of(backend), Optional.of(journal), effects);
    }
    static WarMobilizationService service(Bindings bindings) {
        return new WarMobilizationService(InitialWarDefinitions.create(), WarLoadoutCatalog.initial(), bindings, 128);
    }
    static long drive(WarMobilizationService service, UUID id, WarMobilizationService.Outcome desired, long now) {
        for (int i = 0; i < 80; i++) {
            var result = service.advance(id, ++now);
            if (result.outcome() == desired) return now;
            if (result.outcome() == WarMobilizationService.Outcome.REVIEW || result.outcome() == WarMobilizationService.Outcome.BLOCKED) {
                throw new AssertionError(result);
            }
        }
        throw new AssertionError("workflow did not reach " + desired);
    }
}
