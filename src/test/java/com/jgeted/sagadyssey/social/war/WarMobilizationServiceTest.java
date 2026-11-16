package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.jgeted.sagadyssey.social.war.WarMilitiaTestSupport.*;
import static com.jgeted.sagadyssey.social.war.application.WarMobilizationService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class WarMobilizationServiceTest {
    @Test void missingOrIncompatiblePortsNeverAcceptRealMobilization() {
        var service = service(Bindings.unavailable()); var request = request();
        assertEquals(11, service.preview(request, 0).missing().size());
        assertEquals(BLOCKED, service.begin(request, 0).outcome()); assertTrue(service.records().isEmpty());
        var backend = new Backend(); var journal = new MemoryJournal(); var ready = bindings(backend, journal);
        Map<WarMobilization.Stage, EffectPort> effects = new EnumMap<>(WarMobilization.Stage.class);
        effects.putAll(ready.effects()); effects.remove(WarMobilization.Stage.SUSTAIN);
        assertTrue(service(new Bindings(ready.inspection(), ready.journal(), effects)).preview(request, 0).missing()
                .contains(WarMobilization.Stage.SUSTAIN.capability()));
        effects.put(WarMobilization.Stage.SUSTAIN, new EffectPort() {
            public int protocolVersion() { return 2; }
            public Reply lookup(Effect e) { throw new AssertionError(); }
            public Reply apply(Effect e) { throw new AssertionError(); }
            public Reply cancel(Effect e) { throw new AssertionError(); }
        });
        assertEquals(BLOCKED, service(new Bindings(ready.inspection(), ready.journal(), effects)).begin(request, 0).outcome());
    }
    @Test void noSharedWriteOccursUntilTheExactIntentIsDurable() {
        var backend = new Backend(); var journal = new MemoryJournal(); journal.waiting = true;
        var service = service(bindings(backend, journal)); var request = request(); service.begin(request, 0);
        assertEquals(WAITING, service.advance(request.id(), 1).outcome()); assertEquals(0, backend.calls); assertEquals(0, backend.reads);
        journal.waiting = false; journal.wrongAck = true;
        assertEquals(REVIEW, service.advance(request.id(), 2).outcome()); assertEquals(0, backend.calls);
        journal.wrongAck = false;
        assertEquals(PROGRESSED, service.advance(request.id(), 3).outcome()); assertEquals(1, backend.calls);
    }
    @Test void aFullLifecycleKeepsPayrollThroughReturnsAndReleasesCivilianLaborBeforeClosingCosts() {
        var backend = new Backend(); var journal = new MemoryJournal(); var service = service(bindings(backend, journal));
        var request = request(); assertEquals(ACCEPTED, service.begin(request, 0).outcome());
        long now = drive(service, request.id(), ACTIVE, 0);
        assertEquals(5, backend.calls); assertTrue(service.find(request.id()).orElseThrow().holdsCharacter());
        service.demobilize(request.id(), ++now); now = drive(service, request.id(), RELEASED, now);
        var record = service.find(request.id()).orElseThrow();
        assertEquals(9, backend.calls); assertFalse(record.holdsCharacter()); assertFalse(record.hasOutstandingObligation());
        var close = backend.effects.get(WarMobilization.Stage.CLOSE_PAYROLL);
        assertEquals(record.receipts().get(WarMobilization.Stage.CLAIM).recordedAtTick(), close.serviceStartTick());
        assertEquals(record.receipts().get(WarMobilization.Stage.RELEASE_LABOR).recordedAtTick(), close.serviceEndTick());
        assertTrue(close.serviceEndTick() >= backend.effects.get(WarMobilization.Stage.RETURN_EQUIPMENT).now());
        assertEquals(REPLAYED, service.begin(request, now).outcome()); service.advance(request.id(), ++now); assertEquals(9, backend.calls);
    }
    @Test void losingAReplyAfterAnEffectReconcilesTheSameOperationAfterReloadWithoutRepeatingIt() {
        var backend = new Backend(); backend.throwAfter = WarMobilization.Stage.CLAIM;
        var journal = new MemoryJournal(); var bindings = bindings(backend, journal); var service = service(bindings); var request = request();
        service.begin(request, 0);
        assertEquals(REVIEW, service.advance(request.id(), 1).outcome()); assertEquals(1, backend.calls);
        assertEquals(WarMobilization.Stage.CLAIM, journal.records.get(request.id()).stage());
        var restarted = service(bindings); restarted.advance(request.id(), 2);
        assertEquals(WarMobilization.Stage.OPEN_PAYROLL, restarted.find(request.id()).orElseThrow().stage()); assertEquals(1, backend.calls);
        assertEquals(WarMobilization.operationId(request.id(), WarMobilization.Stage.CLAIM), backend.applied.keySet().iterator().next());
        drive(restarted, request.id(), ACTIVE, 2); assertEquals(5, backend.calls);
    }
    @Test void anUnknownOutcomeCannotBeTreatedAsMissingOrRetriedWithANewId() {
        var backend = new Backend(); backend.unknown = true; var service = service(bindings(backend, new MemoryJournal())); var request = request();
        service.begin(request, 0);
        assertEquals(REVIEW, service.advance(request.id(), 1).outcome()); assertEquals(REVIEW, service.advance(request.id(), 2).outcome());
        assertEquals(0, backend.calls);
        backend.unknown = false; service.advance(request.id(), 3);
        assertEquals(1, backend.calls); assertTrue(backend.applied.containsKey(WarMobilization.operationId(request.id(), WarMobilization.Stage.CLAIM)));
    }
    @Test void foreignReceiptsDoNotActivateOrReleaseTheRequestedPerson() {
        var backend = new Backend(); backend.wrongReceipt = true; var service = service(bindings(backend, new MemoryJournal())); var request = request();
        service.begin(request, 0); assertEquals(REVIEW, service.advance(request.id(), 1).outcome());
        assertEquals(WarMobilization.Stage.CLAIM, service.find(request.id()).orElseThrow().stage());
        assertTrue(service.find(request.id()).orElseThrow().receipts().isEmpty());
        backend.wrongReceipt = false; service.advance(request.id(), 2); assertEquals(1, backend.calls);
    }
    @Test void cancellationBeforeAnyClaimWaitsForProofAndTheFinalCheckpointBeforeAnotherEnlistment() {
        var backend = new Backend(); var service = service(bindings(backend, new MemoryJournal())); var request = request(); service.begin(request, 0);
        service.demobilize(request.id(), 1); service.advance(request.id(), 2); assertEquals(0, backend.calls);
        var another = new WarMilitiaRequest(UUID.randomUUID(), request.actorId(), request.armyId(), request.unitId(), request.characterId(),
                request.loadout(), 1, request.kind(), request.role(), request.wage(), 2);
        assertEquals(BUSY, service.begin(another, 2).outcome());
        assertEquals(RELEASED, service.advance(request.id(), 3).outcome());
        assertEquals(ACCEPTED, service.begin(another, 3).outcome());
    }
    @Test void cancellingInTransitUsesTheStableCancellationAndStillAccountsForTheClaimedInterval() {
        var backend = new Backend(); backend.pending.add(WarMobilization.Stage.EQUIP);
        var service = service(bindings(backend, new MemoryJournal())); var request = request(); service.begin(request, 0);
        service.advance(request.id(), 1); service.advance(request.id(), 2); service.advance(request.id(), 3);
        assertEquals(WarMobilization.Stage.EQUIP, service.find(request.id()).orElseThrow().stage());
        service.demobilize(request.id(), 4); drive(service, request.id(), RELEASED, 4);
        assertEquals(1, backend.cancellations); assertFalse(backend.effects.containsKey(WarMobilization.Stage.EQUIP));
        assertFalse(backend.effects.containsKey(WarMobilization.Stage.RETURN_EQUIPMENT));
        assertTrue(backend.effects.containsKey(WarMobilization.Stage.CLOSE_PAYROLL));
    }
    @Test void cancellingAfterReloadWithALostClaimReplyStillPaysTheActualHeldInterval() {
        var backend = new Backend(); backend.throwAfter = WarMobilization.Stage.CLAIM;
        var journal = new MemoryJournal(); var binding = bindings(backend, journal); var request = request();
        var original = service(binding); original.begin(request, 0);
        assertEquals(REVIEW, original.advance(request.id(), 1).outcome());
        var restarted = service(binding); restarted.demobilize(request.id(), 2);
        drive(restarted, request.id(), RELEASED, 2);
        var close = backend.effects.get(WarMobilization.Stage.CLOSE_PAYROLL);
        assertEquals(1, close.serviceStartTick()); assertTrue(close.serviceEndTick() > close.serviceStartTick());
        assertEquals(4, backend.calls); assertEquals(0, backend.cancellations);
        assertFalse(backend.effects.containsKey(WarMobilization.Stage.SUSTAIN));
        assertFalse(backend.effects.containsKey(WarMobilization.Stage.EQUIP));
        assertTrue(restarted.find(request.id()).orElseThrow().receipts().containsKey(WarMobilization.Stage.RELEASE_LABOR));
    }
    @Test void wageDebtSurvivesReleaseAndDoesNotTurnTheNpcIntoSecurityForPayment() {
        var backend = new Backend(); backend.debt = true; var journal = new MemoryJournal(); var service = service(bindings(backend, journal));
        var request = request(); service.begin(request, 0); long now = drive(service, request.id(), ACTIVE, 0);
        service.demobilize(request.id(), ++now); now = drive(service, request.id(), RELEASED, now);
        var record = service.find(request.id()).orElseThrow(); assertFalse(record.holdsCharacter()); assertTrue(record.hasOutstandingObligation());
        assertTrue(service(bindings(backend, journal)).find(request.id()).orElseThrow().hasOutstandingObligation());
        assertEquals(RELEASED, service.advance(request.id(), ++now).outcome());
    }
    @Test void duplicateRequestsConflictAndOrphanedSharedObligationsBlockNewService() {
        var backend = new Backend(); var journal = new MemoryJournal(); var service = service(bindings(backend, journal)); var request = request();
        service.begin(request, 0);
        var changed = new WarMilitiaRequest(request.id(), request.actorId(), request.armyId(), request.unitId(), request.characterId(),
                request.loadout(), 1, request.kind(), WarMilitiaRequest.Role.ENGINEER, request.wage(), 0);
        assertEquals(CONFLICT, service.begin(changed, 0).outcome());
        backend.recoveryReady = false; service.reconcile(); assertEquals(BLOCKED, service.advance(request.id(), 1).outcome());
        assertEquals(0, backend.calls);
    }
    @Test void clocksThreadsAndOneStepPerTickAreEnforced() {
        var backend = new Backend(); var service = service(bindings(backend, new MemoryJournal())); var request = request(); service.begin(request, 0);
        service.advance(request.id(), 5); assertEquals(WAITING, service.advance(request.id(), 5).outcome()); assertEquals(1, backend.calls);
        assertEquals(BLOCKED, service.advance(request.id(), 4).outcome());
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> service.advance(request.id(), 6))).join();
    }
}
