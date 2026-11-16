package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static com.jgeted.sagadyssey.social.war.WarMilitiaTestSupport.request;
import static org.junit.jupiter.api.Assertions.*;

class WarPreparationServiceTest {
    private static final WarCommandService.Authority ALLOWED = (actor, army) -> WarCommandService.Permission.ALLOWED;
    private static WarCandidateReport observed(WarMilitiaRequest request, long now) {
        return new WarCandidateReport(request.id(), request.characterId(), now,
                Optional.of(new WarCandidateReport.Person(UUID.randomUUID(), "minecraft:overworld", "BLACKSMITH", "SERVICE", 26, 26, 400, now)),
                Optional.empty(), Set.of(), List.of());
    }
    private static WarPreparationService service(WarCommandService.Authority authority,
            BiFunction<List<WarMilitiaRequest>, Long, List<WarCandidateReport>> observer, Set<WarKey> missing) {
        return new WarPreparationService(InitialWarDefinitions.create(), InitialWarDefinitions.LOADOUTS, authority, observer, () -> missing);
    }
    private static WarMilitiaRequest additional(WarMilitiaRequest first) {
        return new WarMilitiaRequest(UUID.randomUUID(), first.actorId(), first.armyId(), UUID.randomUUID(), UUID.randomUUID(), first.loadout(),
                first.definitionVersion(), first.kind(), first.role(), first.wage(), 0);
    }
    @Test void tenPeopleUseOneObservationAndStillNeedEverySharedCapability() {
        var first = request(); var requests = new ArrayList<WarMilitiaRequest>(); requests.add(first);
        for (int i = 1; i < 10; i++) requests.add(additional(first)); var calls = new AtomicInteger();
        var missing = new HashSet<>(Set.of(WarKey.parse("sagadyssey:war/mobilization/claim")));
        var preview = service(ALLOWED, (batch, now) -> { calls.incrementAndGet(); return batch.stream().map(r -> observed(r, now)).toList(); }, missing)
                .prepare(requests, 10, 24_000);
        missing.clear(); requests.clear();
        assertEquals(1, calls.get()); assertEquals(10, preview.observedClearCount()); assertFalse(preview.readyForSubmission());
        assertEquals(BigInteger.TEN, preview.wageBudgetCeiling().get(first.wage().currency()));
        assertEquals(1, preview.missingCapabilities().size()); assertThrows(UnsupportedOperationException.class, () -> preview.entries().clear());
    }
    @Test void deniedArmyNeverReadsNativePeopleOrPrivateEquipment() {
        var preview = service((actor, army) -> WarCommandService.Permission.DENIED,
                (batch, now) -> { throw new AssertionError("unauthorized native read"); }, Set.of()).prepare(List.of(request()), 0, 1);
        var candidate = preview.entries().getFirst().candidate();
        assertTrue(candidate.person().isEmpty()); assertTrue(candidate.equipment().isEmpty()); assertFalse(preview.readyForSubmission());
        assertEquals("ARMY_AUTHORITY_UNAVAILABLE_OR_DENIED", candidate.problems().getFirst().code());
    }
    @Test void duplicatePeopleOrIdsMixedScopesAndOversizedBatchesAreRejectedBeforeQueries() {
        var first = request(); var service = service(ALLOWED, (batch, now) -> { throw new AssertionError("invalid batch observed"); }, Set.of());
        assertThrows(IllegalArgumentException.class, () -> service.prepare(List.of(first, first), 0, 1));
        var another = additional(first);
        var samePerson = new WarMilitiaRequest(another.id(), first.actorId(), first.armyId(), first.unitId(), first.characterId(), first.loadout(),
                1, first.kind(), first.role(), first.wage(), 0);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(List.of(first, samePerson), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(List.of(first, request()), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(Collections.nCopies(129, first), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(List.of(first), 0, 2_400_001));
    }
    @Test void unsupportedDefinitionsAndFutureRequestsDoNotReadTheirBodies() {
        var first = request(); var invalid = new WarMilitiaRequest(UUID.randomUUID(), first.actorId(), first.armyId(), first.unitId(), UUID.randomUUID(),
                WarKey.parse("future:unknown"), 1, first.kind(), first.role(), first.wage(), 0);
        var future = new WarMilitiaRequest(UUID.randomUUID(), first.actorId(), first.armyId(), first.unitId(), UUID.randomUUID(), first.loadout(),
                1, first.kind(), first.role(), first.wage(), 11);
        var preview = service(ALLOWED, (batch, now) -> { assertEquals(List.of(first), batch); return List.of(observed(first, now)); }, Set.of())
                .prepare(List.of(first, invalid, future), 10, 1);
        assertEquals(1, preview.observedClearCount()); assertEquals(3, preview.entries().size()); assertFalse(preview.readyForSubmission());
    }
    @Test void aForeignMissingDuplicateOrOldObservationFailsTheWholeQueriedBatchClosed() {
        var first = request(); var other = additional(first);
        List<BiFunction<List<WarMilitiaRequest>, Long, List<WarCandidateReport>>> bad = List.of(
                (batch, now) -> List.of(observed(first, now)),
                (batch, now) -> List.of(observed(first, now), observed(first, now)),
                (batch, now) -> List.of(observed(first, now), observed(request(), now)),
                (batch, now) -> List.of(observed(first, now - 1), observed(other, now)),
                (batch, now) -> { throw new IllegalStateException("source unavailable"); });
        for (var observer : bad) {
            var preview = service(ALLOWED, observer, Set.of()).prepare(List.of(first, other), 10, 1);
            assertEquals(0, preview.observedClearCount()); assertTrue(preview.entries().stream().allMatch(e -> e.candidate().person().isEmpty()));
        }
    }
    @Test void wageForecastHasAnExplicitRoundingBoundAndCannotOverflowLong() {
        var first = request(); var large = new WarMilitiaRequest(first.id(), first.actorId(), first.armyId(), first.unitId(), first.characterId(),
                first.loadout(), 1, first.kind(), first.role(), new WarMilitiaRequest.Wage(first.wage().currency(), Long.MAX_VALUE, 1), 0);
        var second = additional(first);
        var preview = service(ALLOWED, (batch, now) -> batch.stream().map(r -> observed(r, now)).toList(), Set.of())
                .prepare(List.of(large, second), 0, 2_400_000);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2_400_000)).add(BigInteger.valueOf(100)), preview.wageBudgetCeiling().get(first.wage().currency()));
        var rounded = service(ALLOWED, (batch, now) -> List.of(observed(first, now)), Set.of()).prepare(List.of(first), 0, 1);
        assertEquals(BigInteger.ONE, rounded.wageBudgetCeiling().get(first.wage().currency()));
    }
    @Test void equipmentShortagesRemainSeparateFromNativePersonConflicts() {
        var first = request();
        var preview = service(ALLOWED, (batch, now) -> {
            var base = observed(first, now); var expected = InitialWarDefinitions.LOADOUTS.find(first.loadout()).orElseThrow();
            var equipment = new WarEquipmentSnapshot(expected, Set.of(), false, false, false);
            return List.of(new WarCandidateReport(first.id(), first.characterId(), now, base.person(), Optional.of(equipment), equipment.mismatches(expected), List.of()));
        }, Set.of()).prepare(List.of(first), 0, 1);
        assertTrue(preview.readyForSubmission());
        assertTrue(preview.entries().getFirst().candidate().equipmentGaps().contains(WarEquipmentSnapshot.Mismatch.PRIMARY_WEAPON));
        assertEquals(60, preview.entries().getFirst().candidate().person().orElseThrow().satietyPercent());
    }
}
