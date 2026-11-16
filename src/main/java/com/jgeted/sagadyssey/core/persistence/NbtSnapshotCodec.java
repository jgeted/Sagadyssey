package com.jgeted.sagadyssey.core.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** 有类型标记、格式版本、长度和摘要的快照封套，未知内容整体保留给恢复流程。 */
public abstract class NbtSnapshotCodec<V> implements DurableSnapshotStore.Codec<V> {
    private static final int MAGIC = 0x53474453;
    private final String kind;
    private final int maxBytes, capacity;
    protected NbtSnapshotCodec(String kind, int maxBytes, int capacity) {
        this.kind = Objects.requireNonNull(kind);
        if (maxBytes < 128 || capacity < 1 || capacity > 65_536) throw new IllegalArgumentException("invalid snapshot bounds");
        this.maxBytes = maxBytes; this.capacity = capacity;
    }
    public final int maxBytes() { return maxBytes; }
    public final int capacity() { return capacity; }
    protected abstract UUID identity(V record);
    protected abstract CompoundTag encode(V record);
    protected abstract V decode(CompoundTag tag);
    protected void validate(Collection<V> records) { }
    public final byte[] write(Collection<V> records) throws IOException {
        if (records.size() > capacity) throw new IOException("snapshot record limit exceeded");
        try { validate(records); } catch (RuntimeException exception) { throw new IOException("invalid snapshot records", exception); }
        var root = new CompoundTag(); root.putString("Kind", kind); var list = new ListTag(); Set<UUID> ids = new HashSet<>();
        for (V record : records.stream().sorted(Comparator.comparing(this::identity)).toList()) {
            if (!ids.add(identity(record))) throw new IOException("duplicate snapshot identity"); list.add(encode(record));
        }
        root.put("Records", list); var buffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(buffer)) { NbtIo.write(root, output); }
        byte[] payload = buffer.toByteArray(); if (payload.length > maxBytes - 44) throw new IOException("snapshot byte limit exceeded");
        var envelope = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(envelope)) { output.writeInt(MAGIC); output.writeInt(1); output.writeInt(payload.length); output.write(payload); output.write(hash(payload)); }
        return envelope.toByteArray();
    }
    public final Collection<V> read(byte[] bytes) throws IOException {
        if (bytes.length > maxBytes) throw new IOException("snapshot byte limit exceeded");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("unsupported snapshot envelope");
            int length = input.readInt(); if (length < 0 || length != bytes.length - 44) throw new IOException("invalid snapshot length");
            byte[] payload = input.readNBytes(length); if (!MessageDigest.isEqual(input.readNBytes(32), hash(payload))) throw new IOException("snapshot checksum mismatch");
            try (var source = new DataInputStream(new ByteArrayInputStream(payload))) {
                var root = NbtIo.read(source, NbtAccounter.create(maxBytes * 4L));
                if (root == null || source.available() != 0 || !root.keySet().equals(Set.of("Kind", "Records")) || !kind.equals(root.getStringOr("Kind", ""))
                        || !(root.get("Records") instanceof ListTag list) || list.size() > capacity || !list.isEmpty() && MinecraftApi.elementType(list) != Tag.TAG_COMPOUND) {
                    throw new IOException("invalid snapshot type/records");
                }
                List<V> result = new ArrayList<>(); Set<UUID> ids = new HashSet<>();
                for (Tag raw : list) { V record = decode((CompoundTag) raw); if (!ids.add(identity(record))) throw new IOException("duplicate snapshot identity"); result.add(record); }
                validate(result); return List.copyOf(result);
            }
        } catch (IllegalArgumentException | NullPointerException | NbtAccounterException exception) { throw new IOException("invalid snapshot semantics; preserve original", exception); }
    }
    protected static void fields(CompoundTag tag, Set<String> fields) { if (!tag.keySet().equals(fields)) throw new IllegalArgumentException("unknown/missing snapshot fields"); }
    protected static UUID uuid(CompoundTag tag, String key) { if (!tag.read(key, UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("missing UUID"); return tag.read(key, UUIDUtil.CODEC).orElseThrow(); }
    protected static String string(CompoundTag tag, String key) { if (!MinecraftApi.containsTag(tag, key, Tag.TAG_STRING)) throw new IllegalArgumentException("missing string"); return tag.getStringOr(key, ""); }
    protected static long number(CompoundTag tag, String key) { if (!MinecraftApi.containsTag(tag, key, Tag.TAG_LONG)) throw new IllegalArgumentException("missing long"); return tag.getLongOr(key, 0L); }
    private static byte[] hash(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
}
