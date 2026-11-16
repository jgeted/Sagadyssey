package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarMobilizationRuntime;
import com.jgeted.sagadyssey.social.war.domain.WarState;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 使用实际NBT、DimensionDataStorage及NeoForge落盘，不以文本检查代替存档验收。 */
class WarSavedDataTest {
    @TempDir Path directory;
    private final WarStateCodec codec = new WarStateCodec(WarStateCodec.Limits.defaults());

    private CompoundTag envelope(WarState state) throws IOException {
        var tag = new CompoundTag(); tag.putInt("WarFormatVersion", 1); tag.putBoolean("Enabled", true);
        tag.putByteArray("Checkpoint", codec.write(state)); return tag;
    }
    private Path saveRaw(CompoundTag data) throws IOException {
        var root = new CompoundTag(); root.put("data", data);
        Path file = directory.resolve(WarSavedData.NAME + ".dat"); NbtIo.writeCompressed(root, file); return file;
    }

    @Test void actualDimensionDataStorageWritesAndFreshBoundedLoadRestoresAllReceipts() throws Exception {
        WarState state = WarArchiveStoreTest.historicalState();
        CompoundTag tag = envelope(state); tag.putString("RetainedExtensionField", "keep-me");
        WarSavedData data = WarSavedData.decode(tag, codec);
        var storage = new DimensionDataStorage(directory, DataFixers.getDataFixer(), RegistryAccess.EMPTY);
        storage.set(WarSavedData.FACTORY.type(WarSavedData.NAME), data); data.setDirty(); storage.saveAndJoin();
        var restored = WarSavedData.readDisk(directory, codec).orElseThrow();
        assertTrue(restored.writable()); assertTrue(restored.enabled()); assertFalse(restored.isDirty());
        assertEquals(state, restored.checkpoint());
        assertEquals("keep-me", restored.save(new CompoundTag(), RegistryAccess.EMPTY).getStringOr("RetainedExtensionField", ""));
        restored.enabled(false);
        var freshStorage = new DimensionDataStorage(directory, DataFixers.getDataFixer(), RegistryAccess.EMPTY);
        freshStorage.set(WarSavedData.FACTORY.type(WarSavedData.NAME), restored); freshStorage.saveAndJoin();
        var paused = WarSavedData.readDisk(directory, codec).orElseThrow();
        assertFalse(paused.enabled()); assertEquals(state, paused.checkpoint());
    }

    @Test void futureInnerSchemaKeepsTheOriginalFileAndCannotBeMarkedWritable() throws Exception {
        CompoundTag tag = envelope(WarArchiveStoreTest.historicalState());
        byte[] encoded = tag.getByteArray("Checkpoint").orElse(new byte[0]); ByteBuffer.wrap(encoded).putInt(4, 3);
        tag.putByteArray("Checkpoint", encoded); tag.putString("FutureField", "preserve");
        Path file = saveRaw(tag); byte[] original = Files.readAllBytes(file);
        WarSavedData data = WarSavedData.readDisk(directory, codec).orElseThrow();
        assertFalse(data.writable()); assertEquals("UNSUPPORTED_SCHEMA", data.blockedReason());
        assertThrows(IllegalStateException.class, data::setDirty);
        assertThrows(IllegalStateException.class, data::checkpoint);
        assertEquals(tag, data.retainedTag().orElseThrow());
        try (var storage = new DimensionDataStorage(directory, DataFixers.getDataFixer(), RegistryAccess.EMPTY)) {
            assertThrows(IllegalStateException.class, () -> storage.set(WarSavedData.FACTORY.type(WarSavedData.NAME), data));
            storage.saveAndJoin();
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void futureOuterFormatIsOpaqueAndExposedTagsCannotModifyIt() throws Exception {
        CompoundTag tag = envelope(WarState.empty()); tag.putInt("WarFormatVersion", 2);
        var expected = tag.copy(); var data = WarSavedData.decode(tag, codec);
        tag.putInt("WarFormatVersion", 0); data.retainedTag().orElseThrow().putString("Injected", "no");
        assertFalse(data.writable()); assertEquals(expected, data.retainedTag().orElseThrow());
        assertThrows(IllegalStateException.class, () -> data.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test void badBytesAndMissingPrimaryWithABackupNeverBecomeEmptyWritableData() throws Exception {
        Path file = directory.resolve(WarSavedData.NAME + ".dat"); Files.write(file, new byte[]{1, 2, 3});
        byte[] original = Files.readAllBytes(file);
        var unreadable = WarSavedData.readDisk(directory, codec).orElseThrow();
        assertFalse(unreadable.writable()); assertArrayEquals(original, Files.readAllBytes(file));
        Files.move(file, directory.resolve(WarSavedData.NAME + ".dat_old"));
        var backup = WarSavedData.readDisk(directory, codec).orElseThrow();
        assertFalse(backup.writable()); assertEquals("PRIMARY_MISSING_BACKUP_REQUIRES_REVIEW", backup.blockedReason());
        assertFalse(Files.exists(file));
        Files.move(directory.resolve(WarSavedData.NAME + ".dat_old"), directory.resolve(WarSavedData.NAME + ".dat.old"));
        assertEquals("PRIMARY_MISSING_BACKUP_REQUIRES_REVIEW", WarSavedData.readDisk(directory, codec).orElseThrow().blockedReason());
        assertFalse(Files.exists(file));
    }

    @Test void invalidFieldTypesAndCorruptPayloadAreBlockedInsteadOfUsingNbtDefaults() throws Exception {
        var malformed = envelope(WarState.empty()); malformed.putString("Enabled", "true");
        assertFalse(WarSavedData.decode(malformed, codec).writable());
        var corrupt = envelope(WarState.empty()); byte[] bytes = corrupt.getByteArray("Checkpoint").orElse(new byte[0]); bytes[24] ^= 1;
        corrupt.putByteArray("Checkpoint", bytes);
        var data = WarSavedData.decode(corrupt, codec);
        assertEquals("CORRUPT_CHECKPOINT", data.blockedReason());
        assertEquals(corrupt, data.retainedTag().orElseThrow());
    }

    @Test void aHighlyCompressedOversizedNbtFileIsBoundedAndRetained() throws Exception {
        var tag = envelope(WarState.empty()); tag.putByteArray("OversizedExtension", new byte[2_000_000]);
        Path file = saveRaw(tag); byte[] original = Files.readAllBytes(file);
        var small = new WarStateCodec(new WarStateCodec.Limits(128, 1, 1, 1));
        var data = WarSavedData.readDisk(directory, small).orElseThrow();
        assertFalse(data.writable()); assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void anUnusedWorldCreatesNoDataAndOffThreadMutationIsRejected() {
        assertTrue(WarSavedData.readDisk(directory, codec).isEmpty());
        assertFalse(Files.exists(directory.resolve(WarSavedData.NAME + ".dat")));
        var data = WarSavedData.create(codec);
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, data::setDirty)).join();
        assertFalse(data.isDirty());
    }

    @Test void dispatchHistoryWithoutCoreStateIsRetainedAndPreventsAnEmptyArmy() throws Exception {
        Path file = directory.resolve(com.jgeted.sagadyssey.social.war.adapter.persistence.WarDispatchJournal.FILE_NAME + ".old");
        Files.write(file, new byte[]{3, 2, 1});
        var data = WarServerEvents.readExisting(directory, codec).orElseThrow();
        assertFalse(data.writable()); assertEquals("DISPATCH_WITHOUT_CORE_STATE_REQUIRES_REVIEW", data.blockedReason());
        assertFalse(Files.exists(directory.resolve(WarSavedData.NAME + ".dat"))); assertArrayEquals(new byte[]{3, 2, 1}, Files.readAllBytes(file));
    }
    @Test void mobilizationHistoryWithoutTheCoreWorldStateCannotBecomeAnEmptyWritableArmy() throws Exception {
        Path file = directory.resolve(WarMobilizationRuntime.FILE_NAME); Files.write(file, new byte[]{1, 2, 3});
        var data = WarServerEvents.readExisting(directory, codec).orElseThrow();
        assertFalse(data.writable()); assertEquals("MOBILIZATION_WITHOUT_CORE_STATE_REQUIRES_REVIEW", data.blockedReason());
        assertThrows(IllegalStateException.class, data::checkpoint);
        assertFalse(Files.exists(directory.resolve(WarSavedData.NAME + ".dat")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
    }
}
