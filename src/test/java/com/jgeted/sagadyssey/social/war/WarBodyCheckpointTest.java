package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarBodyCheckpointCodec;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.ReadStatus;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarBodyCheckpointTest {
    @Test void onlyAReceivedOrderForTheOriginalRosterCanAdvanceTheCheckpoint() {
        var f = new WarDispatchServiceTest.Fixture(); var c = f.order(); f.receive(c, 0);
        var value = f.service.effective(f.unit, 6).orElseThrow(); var member = value.members().getFirst();
        var body = WarBodyCheckpoint.receive(Optional.empty(), value, member, 6, 20, ignored -> false).orElseThrow();
        assertEquals(c.operationId(), body.order()); assertEquals(member.contract(), body.contract());
        assertTrue(WarBodyCheckpoint.receive(Optional.empty(), value, member, 4, 20, ignored -> false).isEmpty());
        assertTrue(WarBodyCheckpoint.receive(Optional.empty(), value, new WarTransmission.Member(UUID.randomUUID(), member.character(), member.body()), 6, 20, ignored -> false).isEmpty());
    }
    @Test void olderOrConflictingOrdersAndFutureCooldownsAreRetainedForReview() {
        var f = new WarDispatchServiceTest.Fixture(); var c = f.order(); f.receive(c, 0);
        var value = f.service.effective(f.unit, 6).orElseThrow(); var member = value.members().getFirst();
        var futureSequence = new WarBodyCheckpoint(member.contract(), f.unit, UUID.randomUUID(), value.sequence() + 1, 0);
        var conflictingId = new WarBodyCheckpoint(member.contract(), f.unit, UUID.randomUUID(), value.sequence(), 0);
        var futureCooldown = new WarBodyCheckpoint(member.contract(), f.unit, c.operationId(), value.sequence(), 1000);
        for (var old : List.of(futureSequence, conflictingId, futureCooldown))
            assertTrue(WarBodyCheckpoint.receive(Optional.of(old), value, member, 6, 20, ignored -> true).isEmpty());
    }
    @Test void anotherEnlistmentNeedsReleasedOriginalLaborAndRetainsAttackCooldown() {
        var f = new WarDispatchServiceTest.Fixture(); var c = f.order(); f.receive(c, 0);
        var value = f.service.effective(f.unit, 6).orElseThrow(); var member = value.members().getFirst();
        var previous = new WarBodyCheckpoint(UUID.randomUUID(), f.unit, UUID.randomUUID(), 50, 20);
        assertTrue(WarBodyCheckpoint.receive(Optional.of(previous), value, member, 6, 20, ignored -> false).isEmpty());
        var next = WarBodyCheckpoint.receive(Optional.of(previous), value, member, 6, 20, id -> id.equals(previous.contract())).orElseThrow();
        assertEquals(20, next.nextAttack()); assertEquals(member.contract(), next.contract());
        assertTrue(next.reserveAttack(19, 20).isEmpty()); assertEquals(40, next.reserveAttack(20, 20).orElseThrow().nextAttack());
        assertTrue(next.reserveAttack(Long.MAX_VALUE, 20).isEmpty());
    }
    @Test void theFrozenPreRefactorBodyFormatRoundTripsWithoutFieldChanges() throws Exception {
        String snbt;
        try (var input = getClass().getResourceAsStream("/war/body-checkpoint-m3a-v1.snbt")) { snbt = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8); }
        var owner = new CompoundTag(); var original = TagParser.parseCompoundFully(snbt); owner.put(WarBodyCheckpointCodec.KEY, original.copy());
        var codec = new WarBodyCheckpointCodec(); var decoded = codec.read(owner);
        assertEquals(ReadStatus.PRESENT, decoded.status()); assertEquals(4, decoded.value().orElseThrow().sequence());
        codec.store(owner, decoded.value().orElseThrow()); assertEquals(original, owner.getCompoundOrEmpty(WarBodyCheckpointCodec.KEY));
    }
    @Test void malformedAndUnknownBodyEvidenceIsNotReplacedByAnEmptyCheckpoint() {
        var codec = new WarBodyCheckpointCodec(); var owner = new CompoundTag(); assertEquals(ReadStatus.ABSENT, codec.read(owner).status());
        var bad = new CompoundTag(); bad.putString("Contract", "wrong type"); bad.putLong("NextAttack", -1); owner.put(WarBodyCheckpointCodec.KEY, bad);
        var before = owner.copy(); assertEquals(ReadStatus.INVALID, codec.read(owner).status()); assertEquals(before, owner);
    }
}
