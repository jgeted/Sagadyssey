package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMobilizationCodec;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.Status;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

import static com.jgeted.sagadyssey.social.war.WarMilitiaTestSupport.*;
import static com.jgeted.sagadyssey.social.war.application.WarMobilizationService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class WarMobilizationCodecTest {
    private final WarMobilizationCodec codec = new WarMobilizationCodec(16 * 1024 * 1024, 128);
    private WarMobilization completed() {
        var backend = new Backend(); backend.debt = true;
        var service = service(bindings(backend, new MemoryJournal())); var request = request(); service.begin(request, 0);
        long now = drive(service, request.id(), ACTIVE, 0); service.demobilize(request.id(), ++now); drive(service, request.id(), RELEASED, now);
        return service.find(request.id()).orElseThrow();
    }
    @Test void everyWorkflowStageAndOutstandingDebtRoundTripWithOriginalOperationIds() throws Exception {
        var backend = new Backend(); var service = service(bindings(backend, new MemoryJournal())); var request = request(); service.begin(request, 0);
        for (long tick = 1; tick < 7; tick++) {
            var record = service.find(request.id()).orElseThrow(); assertEquals(List.of(record), codec.read(codec.write(List.of(record))));
            service.advance(request.id(), tick);
        }
        var finished = completed(); assertTrue(finished.hasOutstandingObligation());
        byte[] encoded = codec.write(List.of(finished)); assertEquals(List.of(finished), codec.read(encoded));
        assertArrayEquals(encoded, codec.write(codec.read(encoded)));
    }
    @Test void recordOrderingDoesNotChangeTheEncodedCheckpoint() throws Exception {
        var first = WarMobilization.start(request()); var second = WarMobilization.start(request());
        assertArrayEquals(codec.write(List.of(first, second)), codec.write(List.of(second, first)));
    }
    @Test void truncationCorruptionAndFutureSchemasAreRejected() throws Exception {
        byte[] bytes = codec.write(List.of(completed()));
        for (int length : new int[]{0, 8, bytes.length - 1}) assertThrows(IOException.class, () -> codec.read(Arrays.copyOf(bytes, length)));
        byte[] corrupted = bytes.clone(); corrupted[40] ^= 1; assertThrows(IOException.class, () -> codec.read(corrupted));
        byte[] future = bytes.clone(); ByteBuffer.wrap(future).putInt(4, 2); assertThrows(IOException.class, () -> codec.read(future));
        assertThrows(IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length + 1)));
    }
    @Test void aCorrectChecksumDoesNotPermitUnknownStepsOrFields() throws Exception {
        byte[] bytes = codec.write(List.of(WarMobilization.start(request()))); CompoundTag unknownStep = payload(bytes);
        MinecraftApi.listOfType(unknownStep, "Records", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).putString("Stage", "future_step");
        assertThrows(IOException.class, () -> codec.read(envelope(unknownStep)));
        CompoundTag future = payload(bytes); MinecraftApi.listOfType(future, "Records", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).putString("FutureField", "retain instead of discarding");
        assertThrows(IOException.class, () -> codec.read(envelope(future)));
    }
    @Test void aCorrectChecksumCannotAdmitTwoActiveContractsForOneCharacter() throws Exception {
        byte[] bytes = codec.write(List.of(WarMobilization.start(request()), WarMobilization.start(request())));
        CompoundTag root = payload(bytes); ListTag records = MinecraftApi.listOfType(root, "Records", Tag.TAG_COMPOUND);
        records.getCompoundOrEmpty(1).store("Character", UUIDUtil.CODEC, records.getCompoundOrEmpty(0).read("Character", UUIDUtil.CODEC).orElseThrow());
        assertThrows(IOException.class, () -> codec.read(envelope(root)));
    }
    @Test void foreignReceiptEvidenceCannotBeReboundToAnotherPerson() throws Exception {
        CompoundTag root = payload(codec.write(List.of(completed())));
        MinecraftApi.listOfType(MinecraftApi.listOfType(root, "Records", Tag.TAG_COMPOUND).getCompoundOrEmpty(0), "Receipts", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).store("Character", UUIDUtil.CODEC, UUID.randomUUID());
        assertThrows(IOException.class, () -> codec.read(envelope(root)));
    }
    @Test void configuredByteAndRecordLimitsAreEnforcedWithoutDroppingExistingEntries() throws Exception {
        var small = new WarMobilizationCodec(64, 1);
        assertThrows(IOException.class, () -> small.write(List.of(completed())));
        assertThrows(IOException.class, () -> small.read(codec.write(List.of(completed()))));
        var one = new WarMobilizationCodec(16 * 1024 * 1024, 1);
        assertThrows(IOException.class, () -> one.write(List.of(WarMobilization.start(request()), WarMobilization.start(request()))));
    }
    private static CompoundTag payload(byte[] encoded) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded, 12, encoded.length - 44))) {
            return NbtIo.read(input, NbtAccounter.create(16 * 1024 * 1024));
        }
    }
    private static byte[] envelope(CompoundTag root) throws Exception {
        var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) { NbtIo.write(root, out); }
        byte[] payload = bytes.toByteArray(); var encoded = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(encoded)) {
            out.writeInt(0x53474D42); out.writeInt(1); out.writeInt(payload.length); out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        return encoded.toByteArray();
    }
}
