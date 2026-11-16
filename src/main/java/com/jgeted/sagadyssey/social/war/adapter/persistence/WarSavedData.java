package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.war.domain.WarState;
import com.jgeted.sagadyssey.social.war.runtime.WarSession;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 只拥有战争快照。损坏/未来格式不创建空状态，也不能被标记为可写。 */
public final class WarSavedData extends NbtSavedData {
    public static final String NAME = "sagadyssey_war";
    public static final Factory<WarSavedData> FACTORY = new Factory<>(
            () -> create(new WarStateCodec(WarStateCodec.Limits.defaults())),
            (tag, registries) -> decode(tag, new WarStateCodec(WarStateCodec.Limits.defaults())));
    private static final int FORMAT = 1;
    private final Thread owner = Thread.currentThread();
    private final WarStateCodec codec;
    private CompoundTag retained;
    private WarState initial;
    private WarSession session;
    private boolean enabled;
    private String blockedReason = "";

    private WarSavedData(WarStateCodec codec) { this.codec = Objects.requireNonNull(codec); }
    public static WarSavedData create(WarStateCodec codec) {
        var data = new WarSavedData(codec); data.initial = WarState.empty(); data.retained = new CompoundTag(); return data;
    }

    public static WarSavedData decode(CompoundTag tag, WarStateCodec codec) {
        var data = new WarSavedData(codec); data.retained = tag.copy();
        if (!MinecraftApi.containsTag(tag, "WarFormatVersion", Tag.TAG_INT) || tag.getIntOr("WarFormatVersion", 0) != FORMAT) {
            data.block("UNSUPPORTED_SAVED_DATA_FORMAT"); return data;
        }
        if (!MinecraftApi.containsTag(tag, "Enabled", Tag.TAG_BYTE) || tag.getByteOr("Enabled", (byte)0) < 0 || tag.getByteOr("Enabled", (byte)0) > 1
                || !MinecraftApi.containsTag(tag, "Checkpoint", Tag.TAG_BYTE_ARRAY)) {
            data.block("MALFORMED_SAVED_DATA"); return data;
        }
        try {
            var result = codec.read(tag.getByteArray("Checkpoint").orElse(new byte[0]));
            if (!(result instanceof WarStateCodec.Restored restored)) {
                data.block(((WarStateCodec.Unsupported) result).reason()); return data;
            }
            data.initial = restored.state(); data.enabled = tag.getBooleanOr("Enabled", false);
            // Keep unknown outer fields without retaining a second full historical byte array.
            data.retained.remove("Checkpoint");
        } catch (IOException exception) { data.block("CORRUPT_CHECKPOINT"); }
        return data;
    }

    /** 不调用computeIfAbsent；NBT解压和编码层均有界，读取失败返回封锁对象。 */
    public static Optional<WarSavedData> readDisk(Path dataDirectory, WarStateCodec codec) {
        Path file = dataDirectory.resolve(NAME + ".dat");
        try {
            if (!present(file)) {
                if (present(dataDirectory.resolve(NAME + ".dat_old")) || present(dataDirectory.resolve(NAME + ".dat.old"))) {
                    var blocked = new WarSavedData(codec); blocked.block("PRIMARY_MISSING_BACKUP_REQUIRES_REVIEW"); return Optional.of(blocked);
                }
                return Optional.empty();
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > codec.maxEncodedBytes() + 1_048_576L) {
                throw new IOException("invalid war saved data file");
            }
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(codec.maxEncodedBytes() * 2L + 1_048_576L));
            if (!MinecraftApi.containsTag(root, "data", Tag.TAG_COMPOUND)) throw new IOException("missing war saved data root");
            return Optional.of(decode(root.getCompoundOrEmpty("data"), codec));
        } catch (IOException | RuntimeException exception) {
            var blocked = new WarSavedData(codec); blocked.block("UNREADABLE_SAVED_DATA:" + exception.getClass().getSimpleName());
            return Optional.of(blocked);
        }
    }

    private static boolean present(Path file) throws IOException {
        try { Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
        // Access denied and other I/O failures must not be mistaken for an absent ledger.
    }

    public boolean writable() { checkThread(); return blockedReason.isEmpty(); }
    public String blockedReason() { checkThread(); return blockedReason; }
    public boolean enabled() { checkThread(); return enabled; }
    public Optional<CompoundTag> retainedTag() { checkThread(); return Optional.ofNullable(retained).map(CompoundTag::copy); }
    public WarState checkpoint() {
        requireWritable(); return session == null ? initial : session.checkpoint();
    }
    public void attach(WarSession session) {
        requireWritable();
        if (this.session != null) throw new IllegalStateException("war saved data already attached");
        this.session = Objects.requireNonNull(session); initial = null;
    }
    public void enabled(boolean value) { requireWritable(); if (enabled != value) { enabled = value; setDirty(); } }
    public void block(String reason) {
        checkThread();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("missing block reason");
        if (session != null && session.phase() != WarSession.Phase.CLOSED) session.pause();
        blockedReason = reason; super.setDirty(false);
    }

    @Override public void setDirty(boolean dirty) {
        checkThread();
        if (dirty) requireWritable();
        super.setDirty(dirty);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireWritable();
        CompoundTag result = retained.copy();
        result.putInt("WarFormatVersion", FORMAT); result.putBoolean("Enabled", enabled);
        try { result.putByteArray("Checkpoint", codec.write(checkpoint())); }
        catch (IOException exception) { throw new UncheckedIOException("cannot encode war checkpoint; previous file retained", exception); }
        return result;
    }
    private void requireWritable() {
        checkThread();
        if (!blockedReason.isEmpty()) throw new IllegalStateException("war saved data is read-only: " + blockedReason);
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("war saved data owner thread required");
    }
}
