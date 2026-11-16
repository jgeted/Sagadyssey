package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStateCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarCommandServiceTest.*;

class WarStateCodecTest {
    private final WarStateCodec codec = new WarStateCodec(WarStateCodec.Limits.defaults());

    private WarState sample() {
        var service = service(); service.submit(create(), 10);
        var first = order(service, 10); service.submit(first, 11); service.deliver(UNIT, first.operationId(), 12);
        service.submit(order(service, 20), 13);
        return service.checkpoint();
    }

    @Test void roundTripPreservesPendingCurrentAndOriginalIdempotencyReceipts() throws Exception {
        WarState state = sample();
        byte[] bytes = codec.write(state);
        var result = assertInstanceOf(WarStateCodec.Restored.class, codec.read(bytes));
        assertEquals(state, result.state());
        assertArrayEquals(bytes, codec.write(result.state()));
        var restored = service(result.state(), WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
        var pending = restored.unit(UNIT).orElseThrow().pendingOrders().getFirst();
        assertEquals(WarResult.Status.REPLAYED, restored.submit(state.operations().get(pending.id()).command(), 20).status());
        assertEquals(WarResult.Status.APPLIED, restored.deliver(UNIT, pending.id(), 21).status());
        assertEquals(20, restored.unit(UNIT).orElseThrow().currentOrder().orElseThrow().intent().target().x());
    }

    @Test void unknownTroopAndIntentIdsRemainDataRatherThanBeingReplaced() throws Exception {
        var troop = WarKey.parse("other:custom_troop"); var intent = WarKey.parse("other:custom_order");
        var definitions = new WarDefinitions.Builder()
                .register(new WarDefinitions.Definition(troop, WarDefinitions.Kind.TROOP, 7, Set.of()))
                .register(new WarDefinitions.Definition(intent, WarDefinitions.Kind.ORDER, 4, Set.of())).freeze();
        var service = service(WarState.empty(), WarCommandService.Limits.defaults(), definitions);
        service.submit(new WarCommand.CreateUnit(UUID.randomUUID(), ACTOR, ARMY, UNIT, troop, 7), 1);
        service.submit(new WarCommand.IssueOrder(UUID.randomUUID(), ACTOR, ARMY, UNIT, 1,
                new WarIntent(intent, 4, new WarIntent.Position(WarKey.parse("other:dimension"), -2, 10, 3))), 2);
        var state = assertInstanceOf(WarStateCodec.Restored.class, codec.read(codec.write(service.checkpoint()))).state();
        assertEquals(troop, state.units().get(UNIT).troopType());
        assertEquals(intent, state.units().get(UNIT).pendingOrders().getFirst().intent().kind());
        var withoutPlugin = service(state, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
        assertEquals(WarResult.Status.UNSUPPORTED_DEFINITION,
                withoutPlugin.deliver(UNIT, state.units().get(UNIT).pendingOrders().getFirst().id(), 3).status());
        assertEquals(state, withoutPlugin.checkpoint());
    }

    @Test void aNewerSchemaReturnsImmutableOpaqueBytesInsteadOfAnEmptyState() throws Exception {
        byte[] encoded = codec.write(sample());
        ByteBuffer.wrap(encoded).putInt(4, 3);
        var result = assertInstanceOf(WarStateCodec.Unsupported.class, codec.read(encoded));
        assertEquals("UNSUPPORTED_SCHEMA", result.reason());
        byte[] expected = encoded.clone(); encoded[0] = 0;
        assertArrayEquals(expected, result.originalBytes());
        byte[] exposed = result.originalBytes(); exposed[0] = 0;
        assertArrayEquals(expected, result.originalBytes());
    }

    @Test void anUnknownOperationPreservesTheWholeCheckpointWithoutPartialRecovery() throws Exception {
        byte[] encoded = codec.write(sample());
        byte[] known = "create_unit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] future = "future_unit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean replaced = false;
        for (int i = 12; i < encoded.length - 32 - known.length; i++) {
            if (Arrays.equals(known, Arrays.copyOfRange(encoded, i, i + known.length))) {
                System.arraycopy(future, 0, encoded, i, future.length); replaced = true; break;
            }
        }
        assertTrue(replaced);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(encoded, 12, encoded.length - 32));
        System.arraycopy(hash, 0, encoded, encoded.length - 32, 32);
        var result = assertInstanceOf(WarStateCodec.Unsupported.class, codec.read(encoded));
        assertEquals("UNSUPPORTED_OPERATION", result.reason());
        assertArrayEquals(encoded, result.originalBytes());
    }

    @Test void corruptionTruncationAndTrailingBytesAreRejected() throws Exception {
        byte[] bytes = codec.write(sample());
        byte[] corrupted = bytes.clone(); corrupted[30] ^= 1;
        assertThrows(IOException.class, () -> codec.read(corrupted));
        for (int length : new int[]{0, 8, bytes.length - 1}) {
            assertThrows(IOException.class, () -> codec.read(Arrays.copyOf(bytes, length)));
        }
        assertThrows(IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length + 1)));
    }

    @Test void hostileCountsAreBoundedEvenWhenTheChecksumIsCorrect() throws Exception {
        byte[] bytes = codec.write(sample());
        ByteBuffer.wrap(bytes).putInt(20, Integer.MAX_VALUE);
        byte[] payload = Arrays.copyOfRange(bytes, 12, bytes.length - 32);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
        System.arraycopy(hash, 0, bytes, bytes.length - 32, 32);
        assertThrows(IOException.class, () -> codec.read(bytes));
        var small = new WarStateCodec(new WarStateCodec.Limits(64, 1, 1, 1));
        assertThrows(IOException.class, () -> small.write(sample()));
        assertThrows(IOException.class, () -> small.read(codec.write(sample())));
    }

    @Test void aCheckpointWithoutTheAcceptanceEvidenceIsNotAValidState() {
        WarState state = sample();
        assertThrows(IllegalArgumentException.class, () -> new WarState(state.lastChangedTick(), state.units(), Map.of()));
    }

    @Test void frozenBytesFromThePreviousRealEncoderMigrateWithoutLosingReceipts() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("war/checkpoint-schema-1.sgw")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertEquals("c3f21c5d54c6839d1496dadea63d29c71f29347064a083d01c5214b2c6ad221c",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertEquals(1, ByteBuffer.wrap(bytes).getInt(4));
            var restored = assertInstanceOf(WarStateCodec.Restored.class, codec.read(bytes)).state();
            assertEquals(0, restored.generation()); assertTrue(restored.previousArchive().isEmpty());
            assertEquals(3, restored.operations().size());
            assertEquals(1, restored.units().get(UNIT).currentOrder().orElseThrow().sequence());
            assertEquals(2, restored.units().get(UNIT).pendingOrders().getFirst().sequence());
            var service = service(restored, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
            var command = restored.operations().get(new UUID(0, 6)).command();
            assertEquals(WarResult.Status.REPLAYED, service.submit(command, 14).status());
            byte[] migrated = codec.write(restored);
            assertEquals(2, ByteBuffer.wrap(migrated).getInt(4));
            assertEquals(restored, assertInstanceOf(WarStateCodec.Restored.class, codec.read(migrated)).state());
        }
    }
}
