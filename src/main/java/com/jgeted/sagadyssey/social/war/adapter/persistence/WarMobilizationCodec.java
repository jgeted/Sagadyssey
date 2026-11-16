package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import net.minecraft.nbt.*;

import java.io.*;
import java.security.*;
import java.util.*;

/** 民兵意图和共享回执引用的有界格式；不序列化NPC、库存或生活职业的可写副本。 */
public final class WarMobilizationCodec {
    private static final int MAGIC = 0x53474D42, SCHEMA = 1;
    private final int maxBytes, maxRecords;
    public WarMobilizationCodec(int maxBytes, int maxRecords) {
        if (maxBytes < 64 || maxRecords < 1 || maxRecords > WarMobilizationService.RECORD_LIMIT) throw new IllegalArgumentException("invalid mobilization codec limits");
        this.maxBytes = maxBytes; this.maxRecords = maxRecords;
    }
    public int maxBytes() { return maxBytes; }
    public int maxRecords() { return maxRecords; }
    public byte[] write(Collection<WarMobilization> records) throws IOException {
        if (records.size() > maxRecords) throw new IOException("too many mobilization records");
        Set<UUID> ids = new HashSet<>(), held = new HashSet<>(); var list = new ListTag();
        for (var record : records.stream().sorted(Comparator.comparing(value -> value.request().id())).toList()) {
            if (!ids.add(record.request().id())) throw new IOException("duplicate mobilization record");
            if (record.holdsCharacter() && !held.add(record.request().characterId())) throw new IOException("duplicate active character");
            list.add(writeRecord(record));
        }
        var root = new CompoundTag(); root.put("Records", list);
        var payloadBuffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(payloadBuffer)) { NbtIo.write(root, output); }
        byte[] payload = payloadBuffer.toByteArray();
        if (payload.length > maxBytes - 44) throw new IOException("mobilization checkpoint exceeds byte limit");
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeInt(SCHEMA); output.writeInt(payload.length);
            output.write(payload); output.write(hash(payload));
        }
        return bytes.toByteArray();
    }
    public List<WarMobilization> read(byte[] bytes) throws IOException {
        if (bytes.length > maxBytes) throw new IOException("mobilization checkpoint exceeds byte limit");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) throw new IOException("unsupported mobilization checkpoint");
            int length = input.readInt();
            if (length < 0 || length != bytes.length - 44) throw new IOException("invalid mobilization envelope");
            byte[] payload = input.readNBytes(length), expected = input.readNBytes(32);
            if (!MessageDigest.isEqual(expected, hash(payload))) throw new IOException("mobilization checksum mismatch");
            try (var source = new DataInputStream(new ByteArrayInputStream(payload))) {
                CompoundTag root = NbtIo.read(source, NbtAccounter.create(maxBytes * 4L));
                if (root == null || source.available() != 0 || !root.keySet().equals(Set.of("Records"))) throw new IOException("invalid mobilization root");
                ListTag list = compounds(root, "Records");
                if (list.size() > maxRecords) throw new IOException("too many mobilization records");
                List<WarMobilization> result = new ArrayList<>(); Set<UUID> ids = new HashSet<>(), held = new HashSet<>();
                for (Tag raw : list) {
                    WarMobilization record = readRecord((CompoundTag) raw);
                    if (!ids.add(record.request().id()) || record.holdsCharacter() && !held.add(record.request().characterId())) {
                        throw new IOException("duplicate mobilization or active character");
                    }
                    result.add(record);
                }
                return List.copyOf(result);
            }
        } catch (IllegalArgumentException | NullPointerException | NbtAccounterException exception) {
            throw new IOException("inconsistent mobilization checkpoint; preserve original", exception);
        }
    }
    private static CompoundTag writeRecord(WarMobilization record) {
        var r = record.request(); var tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, r.id()); tag.store("Actor", UUIDUtil.CODEC, r.actorId()); tag.store("Army", UUIDUtil.CODEC, r.armyId());
        tag.store("Unit", UUIDUtil.CODEC, r.unitId()); tag.store("Character", UUIDUtil.CODEC, r.characterId()); tag.putString("Loadout", r.loadout().toString());
        tag.putInt("DefinitionVersion", r.definitionVersion()); tag.putString("Kind", r.kind().name()); tag.putString("Role", r.role().name());
        tag.putString("Currency", r.wage().currency().toString()); tag.putLong("Wage", r.wage().amountPerPeriod());
        tag.putLong("Period", r.wage().periodTicks()); tag.putLong("Requested", r.requestedAtTick());
        tag.putString("Stage", record.stage().key()); tag.putLong("Revision", record.revision());
        tag.putLong("Changed", record.changedAtTick()); tag.putBoolean("Demobilize", record.demobilizeRequested()); tag.putString("Review", record.reviewReason());
        var receipts = new ListTag();
        record.receipts().values().stream().sorted(Comparator.comparing(receipt -> receipt.stage().key())).forEach(receipt -> {
            var entry = new CompoundTag(); entry.store("Contract", UUIDUtil.CODEC, receipt.contractId()); entry.store("Character", UUIDUtil.CODEC, receipt.characterId());
            entry.putString("Stage", receipt.stage().key()); entry.store("Operation", UUIDUtil.CODEC, receipt.operationId()); entry.store("Reference", UUIDUtil.CODEC, receipt.referenceId());
            entry.putLong("Recorded", receipt.recordedAtTick()); entry.putBoolean("Outstanding", receipt.outstandingObligation()); receipts.add(entry);
        });
        tag.put("Receipts", receipts); return tag;
    }
    private static WarMobilization readRecord(CompoundTag tag) throws IOException {
        if (!tag.keySet().equals(Set.of("Id", "Actor", "Army", "Unit", "Character", "Loadout", "DefinitionVersion", "Kind", "Role",
                "Currency", "Wage", "Period", "Requested", "Stage", "Revision", "Changed", "Demobilize", "Review", "Receipts"))) {
            throw new IOException("unknown or missing mobilization fields");
        }
        var request = new WarMilitiaRequest(uuid(tag, "Id"), uuid(tag, "Actor"), uuid(tag, "Army"), uuid(tag, "Unit"), uuid(tag, "Character"),
                WarKey.parse(string(tag, "Loadout")), integer(tag, "DefinitionVersion"), WarMilitiaRequest.Kind.valueOf(string(tag, "Kind")),
                WarMilitiaRequest.Role.valueOf(string(tag, "Role")), new WarMilitiaRequest.Wage(WarKey.parse(string(tag, "Currency")),
                number(tag, "Wage"), number(tag, "Period")), number(tag, "Requested"));
        Map<WarMobilization.Stage, WarMobilization.Receipt> receipts = new EnumMap<>(WarMobilization.Stage.class);
        ListTag entries = compounds(tag, "Receipts");
        if (entries.size() > 9) throw new IOException("too many mobilization receipts");
        for (Tag raw : entries) {
            var entry = (CompoundTag) raw; var stage = WarMobilization.Stage.fromKey(string(entry, "Stage"));
            if (!entry.keySet().equals(Set.of("Contract", "Character", "Stage", "Operation", "Reference", "Recorded", "Outstanding"))) {
                throw new IOException("unknown or missing mobilization receipt fields");
            }
            var receipt = new WarMobilization.Receipt(uuid(entry, "Contract"), uuid(entry, "Character"), stage,
                    uuid(entry, "Operation"), uuid(entry, "Reference"), number(entry, "Recorded"), flag(entry, "Outstanding"));
            if (receipts.putIfAbsent(stage, receipt) != null) throw new IOException("duplicate mobilization receipt");
        }
        return new WarMobilization(request, WarMobilization.Stage.fromKey(string(tag, "Stage")), number(tag, "Revision"),
                number(tag, "Changed"), flag(tag, "Demobilize"), receipts, string(tag, "Review"));
    }
    private static UUID uuid(CompoundTag tag, String key) throws IOException {
        if (!tag.read(key, UUIDUtil.CODEC).isPresent()) throw new IOException("missing UUID: " + key); return tag.read(key, UUIDUtil.CODEC).orElseThrow();
    }
    private static String string(CompoundTag tag, String key) throws IOException { type(tag, key, Tag.TAG_STRING); return tag.getStringOr(key, ""); }
    private static long number(CompoundTag tag, String key) throws IOException { type(tag, key, Tag.TAG_LONG); return tag.getLongOr(key, 0L); }
    private static int integer(CompoundTag tag, String key) throws IOException { type(tag, key, Tag.TAG_INT); return tag.getIntOr(key, 0); }
    private static boolean flag(CompoundTag tag, String key) throws IOException {
        type(tag, key, Tag.TAG_BYTE); if (tag.getByteOr(key, (byte)0) != 0 && tag.getByteOr(key, (byte)0) != 1) throw new IOException("invalid boolean"); return tag.getBooleanOr(key, false);
    }
    private static ListTag compounds(CompoundTag tag, String key) throws IOException {
        type(tag, key, Tag.TAG_LIST); ListTag list = MinecraftApi.listOfType(tag, key, Tag.TAG_COMPOUND);
        if (((ListTag) tag.get(key)).size() != list.size()) throw new IOException("invalid compound list"); return list;
    }
    private static void type(CompoundTag tag, String key, int type) throws IOException { if (!MinecraftApi.containsTag(tag, key, type)) throw new IOException("missing/wrong field: " + key); }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
