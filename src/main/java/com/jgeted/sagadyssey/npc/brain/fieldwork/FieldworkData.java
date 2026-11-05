package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;

/**
 * 阶段 4 服务端检查点：检索游标、暂时不可达记忆和单方块回执。
 * 回执不可静默丢弃，否则重载后的重试可能重复收获；未接入普通玩家同步接口。
 */
public final class FieldworkData extends SavedData implements FieldworkCommitService.Ledger {
    public static final String DATA_NAME = "sagadyssey_fieldwork_v3";
    public static final int SCHEMA = 2;
    public static final Factory<FieldworkData> FACTORY = new Factory<>(FieldworkData::new, FieldworkData::load);
    private static final int MAX_UNREACHABLE_PER_NPC = 256;

    private final Map<UUID, FieldworkScan.Cursor> scans = new HashMap<>();
    private final Map<UUID, Map<TargetKey, Unreachable>> unreachable = new HashMap<>();
    private final Map<String, FieldworkActionReceipt> receipts = new HashMap<>();
    private final Map<UUID, FieldworkCargo.Delivery> deliveries = new HashMap<>();
    private final Map<String, FieldworkCargo.OutputConsumption> outputConsumptions = new HashMap<>();
    private final Map<UUID, FieldworkStorage> testStorage = new HashMap<>();
    private final Map<UUID, FieldworkStorage.Pending> pendingDeliveries = new HashMap<>();
    private final Map<UUID, Long> deliveryRetryAt = new HashMap<>();
    private final Map<UUID, String> searchProfiles = new HashMap<>();
    public synchronized boolean hasSearchProfile(UUID npcId, String profile) { return profile.equals(searchProfiles.get(npcId)); }
    public synchronized FieldworkScan.Cursor search(UUID npcId, String profile, FieldworkSearchArea area) {
        if (!Objects.equals(searchProfiles.put(npcId, profile), profile)) { scans.remove(npcId); setDirty(); }
        return scanFor(npcId, area);
    }
    public synchronized long deliveryRetryAt(UUID npcId) { return deliveryRetryAt.getOrDefault(npcId, 0L); }
    public synchronized void deferDelivery(UUID npcId, long retryAt) {
        if (retryAt < 0) throw new IllegalArgumentException("invalid delivery deadline");
        deliveryRetryAt.put(npcId, retryAt); setDirty();
    }

    public synchronized void bindTestStorage(UUID npcId, FieldworkStorage storage) {
        testStorage.put(Objects.requireNonNull(npcId), Objects.requireNonNull(storage)); setDirty();
    }
    public synchronized Optional<FieldworkStorage> testStorage(UUID npcId) { return Optional.ofNullable(testStorage.get(npcId)); }
    public synchronized Optional<FieldworkStorage.Pending> pendingDelivery(UUID npcId) { return Optional.ofNullable(pendingDeliveries.get(npcId)); }
    public synchronized void beginDelivery(FieldworkStorage.Pending intent) {
        var existing = pendingDeliveries.get(intent.npcId());
        if (existing != null && !existing.equals(intent)) throw new IllegalStateException("another delivery is pending");
        pendingDeliveries.put(intent.npcId(), intent); setDirty();
    }
    public synchronized void finishDelivery(UUID npcId, UUID transferId) {
        var pending = pendingDeliveries.get(npcId);
        if (pending != null && pending.transferId().equals(transferId)) { pendingDeliveries.remove(npcId); setDirty(); }
    }

    public synchronized void delivered(FieldworkCargo.Delivery delivery) {
        var previous = deliveries.get(delivery.transferId());
        if (previous != null && !previous.equals(delivery)) throw new IllegalStateException("conflicting fieldwork delivery");
        if (previous == null) {
            deliveries.put(delivery.transferId(), delivery);
            setDirty();
        }
    }

    public synchronized Map<String, Integer> pendingCargo(UUID npcId) {
        return FieldworkCargo.pending(receiptsFor(npcId), deliveries.values().stream()
                .filter(value -> value.npcId().equals(npcId)).toList(), outputConsumptions(npcId));
    }

    /** Immutable actual delivery facts for normal capacity decisions and read-only audits. */
    public synchronized List<FieldworkCargo.Delivery> deliveriesFor(UUID body) {
        return deliveries.values().stream().filter(d->d.npcId().equals(body))
                .sorted(Comparator.comparingLong(FieldworkCargo.Delivery::committedAt).thenComparing(d->d.transferId().toString())).toList();
    }
    public synchronized Optional<FieldworkCargo.OutputConsumption> outputConsumption(String key) {
        return Optional.ofNullable(outputConsumptions.get(key));
    }
    public synchronized List<FieldworkCargo.OutputConsumption> outputConsumptions(UUID npcId) {
        return outputConsumptions.values().stream().filter(value -> value.npcId().equals(npcId))
                .sorted(Comparator.comparingLong(FieldworkCargo.OutputConsumption::startedAt)
                        .thenComparing(FieldworkCargo.OutputConsumption::key)).toList();
    }
    public synchronized boolean hasUnresolvedOutputConsumption(UUID npcId) {
        return outputConsumptions(npcId).stream().anyMatch(value -> value.state() == FieldworkCargo.ConsumptionState.PENDING);
    }
    /** Ambiguous cross-ledger settlement is retained and must not be replayed or counted as free output. */
    public synchronized String outputConsumptionReview(UUID npcId, java.util.function.Function<String,String> lifeSettlement) {
        for (var value : outputConsumptions(npcId)) {
            String life = lifeSettlement.apply(value.key());
            if (value.state() == FieldworkCargo.ConsumptionState.PENDING)
                return "产出消费尚未核实，保留物品与原义务: " + value.key() + "; life=" + life;
            if (!"DONE".equals(life)) return "产出消费与生活回执不一致，禁止重放: " + value.key() + "; life=" + life;
        }
        return "";
    }
    public synchronized boolean beginOutputConsumption(FieldworkCargo.OutputConsumption intent) {
        if (intent.state() != FieldworkCargo.ConsumptionState.PENDING) throw new IllegalArgumentException("expected pending output consumption");
        var previous = outputConsumptions.get(intent.key());
        if (previous != null) {
            if (!previous.equals(intent)) throw new IllegalStateException("conflicting output consumption key");
            return false;
        }
        if (hasUnresolvedOutputConsumption(intent.npcId()) || pendingDeliveries.containsKey(intent.npcId()))
            throw new IllegalStateException("prior output settlement requires reconciliation");
        if (pendingCargo(intent.npcId()).getOrDefault(intent.fingerprint(), 0) < intent.outputQuantity())
            throw new IllegalStateException("output consumption has no matching uncommitted production");
        outputConsumptions.put(intent.key(), intent); setDirty(); return true;
    }
    public synchronized void commitOutputConsumption(String key, int actualConsumed, long finalRevision, long now) {
        var previous = outputConsumptions.get(key);
        if (previous == null) throw new IllegalStateException("missing output consumption intent");
        var committed = previous.commit(actualConsumed, finalRevision, now);
        if (previous.state() == FieldworkCargo.ConsumptionState.COMMITTED) {
            if (!previous.equals(committed)) throw new IllegalStateException("conflicting committed output consumption");
            return;
        }
        var candidate = new ArrayList<>(outputConsumptions(previous.npcId()));
        candidate.removeIf(value -> value.key().equals(key)); candidate.add(committed);
        FieldworkCargo.pending(receiptsFor(previous.npcId()), deliveries.values().stream()
                .filter(value -> value.npcId().equals(previous.npcId())).toList(), candidate);
        outputConsumptions.put(key, committed); setDirty();
    }
    /** Only a consumption service failure with rolled-back physical goods may cancel an uncommitted intent. */
    public synchronized void abortOutputConsumption(String key) {
        var previous = outputConsumptions.get(key);
        if (previous == null) return;
        if (previous.state() != FieldworkCargo.ConsumptionState.PENDING) throw new IllegalStateException("committed consumption cannot be erased");
        outputConsumptions.remove(key); setDirty();
    }

    /** 最近一次放置尚未有后续回收回执的脚手架，即使所属计划已取消仍保留义务。 */
    public synchronized List<FieldworkActionReceipt> outstandingScaffolds(UUID npcId) {
        Map<String, FieldworkActionReceipt> outstanding = new HashMap<>();
        for (var receipt : receiptsFor(npcId)) {
            String location = receipt.action().dimension() + ":" + receipt.action().target().asLong();
            if (receipt.action().operation() == FieldworkAction.Operation.PLACE_SCAFFOLD) outstanding.put(location, receipt);
            if (receipt.action().operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD) outstanding.remove(location);
        }
        return outstanding.values().stream().sorted(Comparator.comparingInt(
                (FieldworkActionReceipt value) -> value.action().target().getY()).reversed()).toList();
    }

    public static FieldworkData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        var storage = server.overworld().getDataStorage();
        FieldworkData loaded = storage.get(FACTORY, DATA_NAME);
        if (loaded != null) return loaded;
        // DimensionDataStorage 会吞掉反序列化/IO 异常并返回 null。
        // computeIfAbsent 此时会创建空账本，导致已有动作被重复执行，故不能使用它。
        FieldworkData resolved = resolveLoaded(loaded, Files.notExists(
                server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat")));
        storage.set(DATA_NAME, resolved);
        return resolved;
    }

    static FieldworkData resolveLoaded(FieldworkData loaded, boolean knownMissingFile) {
        if (loaded != null) return loaded;
        if (!knownMissingFile) {
            throw new IllegalStateException("fieldwork checkpoint exists or is inaccessible; refusing empty ledger fallback");
        }
        return new FieldworkData();
    }

    public synchronized Optional<FieldworkScan.Cursor> scan(UUID npcId) {
        return Optional.ofNullable(scans.get(Objects.requireNonNull(npcId, "npcId")));
    }

    public synchronized void saveScan(UUID npcId, FieldworkScan.Cursor cursor) {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(cursor, "cursor");
        if (!cursor.equals(scans.put(npcId, cursor))) setDirty();
    }

    /** 工作区发生变化时必须开始新一轮，不能继续解释旧索引。 */
    public synchronized FieldworkScan.Cursor scanFor(UUID npcId, FieldworkSearchArea area) {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(area, "area");
        FieldworkScan.Cursor cursor = scans.get(npcId);
        if (cursor == null || !cursor.area().equals(area)) {
            cursor = FieldworkScan.Cursor.begin(area);
            saveScan(npcId, cursor);
        }
        return cursor;
    }

    public record Unreachable(String dimension, BlockPos target, long retryAt, String reason) {
        public Unreachable {
            FieldworkAction.requireText(dimension, 256, "dimension");
            target = Objects.requireNonNull(target, "target").immutable();
            FieldworkAction.requirePosition(target);
            FieldworkAction.requireText(reason, 2_048, "reason");
            if (retryAt <= 0L) throw new IllegalArgumentException("retryAt must be positive");
        }
    }

    private record TargetKey(String dimension, BlockPos position) {
    }

    public synchronized void rememberUnreachable(UUID npcId, String dimension, BlockPos target,
                                                  long now, long retryAt, String reason) {
        Objects.requireNonNull(npcId, "npcId");
        if (now < 0L || retryAt <= now) throw new IllegalArgumentException("retry must follow now");
        Unreachable memory = new Unreachable(dimension, target, retryAt, reason);
        Map<TargetKey, Unreachable> memories = unreachable.computeIfAbsent(npcId, ignored -> new HashMap<>());
        memories.values().removeIf(value -> value.retryAt() <= now);
        memories.put(new TargetKey(dimension, memory.target()), memory);
        while (memories.size() > MAX_UNREACHABLE_PER_NPC) {
            Unreachable earliest = sortedMemories(memories).getFirst();
            memories.remove(new TargetKey(earliest.dimension(), earliest.target()));
        }
        setDirty();
    }

    public synchronized Optional<Unreachable> unreachable(UUID npcId, String dimension,
                                                           BlockPos target, long now) {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(target, "target");
        if (now < 0L) throw new IllegalArgumentException("negative game time");
        Unreachable memory = unreachable.getOrDefault(npcId, Map.of())
                .get(new TargetKey(dimension, target));
        return memory != null && memory.retryAt() > now ? Optional.of(memory) : Optional.empty();
    }

    /** 管理员审计保留过期记录；查询不会顺手删掉证据。 */
    public synchronized List<Unreachable> memories(UUID npcId) {
        return sortedMemories(unreachable.getOrDefault(Objects.requireNonNull(npcId, "npcId"), Map.of()));
    }

    @Override
    public synchronized Optional<FieldworkActionReceipt> receipt(String key) {
        return Optional.ofNullable(receipts.get(Objects.requireNonNull(key, "key")));
    }

    /** 先验证冲突再写入；失败不能留下半个回执。 */
    @Override
    public synchronized void record(FieldworkActionReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        FieldworkActionReceipt previous = receipts.get(receipt.action().key());
        if (previous != null && !previous.equals(receipt)) {
            throw new IllegalStateException("conflicting fieldwork receipt");
        }
        if (previous == null) {
            receipts.put(receipt.action().key(), receipt);
            setDirty();
        }
    }

    public synchronized List<FieldworkActionReceipt> receiptsFor(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId");
        return receipts.values().stream().filter(value -> value.action().npcId().equals(npcId))
                .sorted(Comparator.comparingLong(FieldworkActionReceipt::committedAt)
                        .thenComparing(value -> value.action().key())).toList();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Schema", SCHEMA);
        ListTag scanTags = new ListTag();
        scans.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag scan = saveArea(entry.getValue().area());
            scan.putUUID("NpcId", entry.getKey());
            scan.putLong("NextIndex", entry.getValue().nextIndex());
            scan.putBoolean("SawUnloaded", entry.getValue().sawUnloaded());
            scan.putLong("RetryAt", entry.getValue().retryAt());
            scanTags.add(scan);
        });
        tag.put("Scans", scanTags);
        ListTag memoryTags = new ListTag();
        unreachable.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            for (Unreachable memory : sortedMemories(entry.getValue())) {
                CompoundTag encoded = new CompoundTag();
                encoded.putUUID("NpcId", entry.getKey());
                encoded.putString("Dimension", memory.dimension());
                encoded.putLong("Target", memory.target().asLong());
                encoded.putLong("RetryAt", memory.retryAt());
                encoded.putString("Reason", memory.reason());
                memoryTags.add(encoded);
            }
        });
        tag.put("Unreachable", memoryTags);
        ListTag receiptTags = new ListTag();
        receipts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> receiptTags.add(saveReceipt(entry.getValue())));
        tag.put("Receipts", receiptTags);
        ListTag deliveryTags = new ListTag();
        deliveries.values().stream().sorted(Comparator.comparing(value -> value.transferId().toString())).forEach(value -> {
            CompoundTag encoded = new CompoundTag();
            encoded.putUUID("TransferId", value.transferId());
            encoded.putUUID("NpcId", value.npcId());
            encoded.putString("Fingerprint", value.fingerprint());
            encoded.putInt("Quantity", value.quantity());
            encoded.putLong("CommittedAt", value.committedAt());
            deliveryTags.add(encoded);
        });
        tag.put("Deliveries", deliveryTags);
        ListTag consumptionTags = new ListTag();
        outputConsumptions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = entry.getValue(); var encoded = new CompoundTag();
            encoded.putString("Key", value.key()); encoded.putUUID("NpcId", value.npcId());
            encoded.putUUID("PersonId", value.personId()); encoded.putUUID("ContainerId", value.containerId());
            encoded.put("Owner", value.owner().save()); encoded.putString("Fingerprint", value.fingerprint());
            encoded.putInt("Quantity", value.quantity()); encoded.putInt("OutputQuantity", value.outputQuantity());
            encoded.putLong("StartedAt", value.startedAt()); encoded.putLong("CommittedAt", value.committedAt());
            encoded.putLong("BeforeRevision", value.beforeRevision()); encoded.putLong("FinalRevision", value.finalRevision());
            encoded.putString("State", value.state().name()); consumptionTags.add(encoded);
        });
        tag.put("OutputConsumptions", consumptionTags);
        ListTag storageTags = new ListTag();
        testStorage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag encoded = entry.getValue().save(); encoded.putUUID("NpcId", entry.getKey()); storageTags.add(encoded);
        });
        tag.put("TestStorage", storageTags);
        ListTag pendingTags = new ListTag();
        pendingDeliveries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> pendingTags.add(entry.getValue().save()));
        tag.put("PendingDeliveries", pendingTags);
        ListTag deliveryRetryTags = new ListTag();
        deliveryRetryAt.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag retry = new CompoundTag(); retry.putUUID("NpcId", entry.getKey()); retry.putLong("RetryAt", entry.getValue()); deliveryRetryTags.add(retry);
        });
        tag.put("DeliveryRetry", deliveryRetryTags);
        ListTag profileTags = new ListTag();
        searchProfiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag profile = new CompoundTag(); profile.putUUID("NpcId", entry.getKey()); profile.putString("Profile", entry.getValue()); profileTags.add(profile);
        });
        tag.put("SearchProfiles", profileTags);
        return tag;
    }

    public static FieldworkData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || !tag.contains("Schema", Tag.TAG_INT) || (tag.getInt("Schema") != 1 && tag.getInt("Schema") != SCHEMA)) {
            throw new IllegalStateException("unsupported fieldwork checkpoint schema");
        }
        if (tag.getInt("Schema") == 1 && tag.contains("OutputConsumptions"))
            throw new IllegalStateException("schema1 cannot silently discard output consumption records");
        FieldworkData data = new FieldworkData();
        for (Tag entry : requireList(tag, "Scans")) {
            CompoundTag scan = (CompoundTag) entry;
            try {
                if (!scan.contains("SawUnloaded", Tag.TAG_BYTE)) {
                    throw new IllegalArgumentException("missing scan loading evidence");
                }
                data.scans.put(uuid(scan, "NpcId"), new FieldworkScan.Cursor(loadArea(scan),
                        number(scan, "NextIndex"), scan.getBoolean("SawUnloaded"), number(scan, "RetryAt")));
            } catch (IllegalArgumentException exception) {
                // 无效游标可安全重新检索，但不能同样忽略已提交的回执。
            }
        }
        for (Tag entry : requireList(tag, "Unreachable")) {
            CompoundTag memory = (CompoundTag) entry;
            try {
                long retryAt = number(memory, "RetryAt");
                data.rememberUnreachable(uuid(memory, "NpcId"), memory.getString("Dimension"),
                        BlockPos.of(number(memory, "Target")), 0L, retryAt, memory.getString("Reason"));
            } catch (IllegalArgumentException exception) {
                // 损坏的短期记忆不会阻止其余检查点读取。
            }
        }
        for (Tag entry : requireList(tag, "Receipts")) {
            try {
                data.record(loadReceipt((CompoundTag) entry));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("invalid fieldwork receipt; refusing unsafe replay", exception);
            }
        }
        // 最初的阶段4基础检查点没有 Deliveries；不存在时等价于尚未交付。
        if (tag.contains("Deliveries")) {
            for (Tag entry : requireList(tag, "Deliveries")) {
                CompoundTag delivery = (CompoundTag) entry;
                data.delivered(new FieldworkCargo.Delivery(uuid(delivery, "TransferId"), uuid(delivery, "NpcId"),
                        delivery.getString("Fingerprint"), integer(delivery, "Quantity"), number(delivery, "CommittedAt")));
            }
        }
        if (tag.contains("TestStorage")) for (Tag entry : requireList(tag, "TestStorage")) {
            CompoundTag storage = (CompoundTag) entry;
            data.bindTestStorage(uuid(storage, "NpcId"), FieldworkStorage.load(storage));
        }
        if (tag.contains("PendingDeliveries")) for (Tag entry : requireList(tag, "PendingDeliveries")) {
            data.beginDelivery(FieldworkStorage.Pending.load((CompoundTag) entry));
        }
        if (tag.contains("DeliveryRetry")) for (Tag entry : requireList(tag, "DeliveryRetry")) {
            CompoundTag retry = (CompoundTag) entry; data.deferDelivery(uuid(retry, "NpcId"), number(retry, "RetryAt"));
        }
        if (tag.contains("SearchProfiles")) for (Tag entry : requireList(tag, "SearchProfiles")) {
            CompoundTag profile = (CompoundTag) entry;
            data.searchProfiles.put(uuid(profile, "NpcId"), FieldworkAction.requireText(profile.getString("Profile"), 1_024, "profile"));
        }
        if (tag.getInt("Schema") == SCHEMA) {
            try {
                var unresolvedBodies = new java.util.HashSet<UUID>();
                for (Tag entry : requireList(tag, "OutputConsumptions")) {
                    var encoded = (CompoundTag) entry;
                    if (!encoded.contains("Owner", Tag.TAG_COMPOUND) || !encoded.getCompound("Owner").hasUUID("Id"))
                        throw new IllegalArgumentException("missing output consumption owner");
                    var value = new FieldworkCargo.OutputConsumption(encoded.getString("Key"), uuid(encoded, "NpcId"),
                            uuid(encoded, "PersonId"), uuid(encoded, "ContainerId"),
                            com.jgeted.sagadyssey.worldsim.rights.SubjectRef.load(encoded.getCompound("Owner")),
                            encoded.getString("Fingerprint"), integer(encoded, "Quantity"), integer(encoded, "OutputQuantity"),
                            number(encoded, "StartedAt"), number(encoded, "CommittedAt"), number(encoded, "BeforeRevision"),
                            number(encoded, "FinalRevision"), FieldworkCargo.ConsumptionState.valueOf(encoded.getString("State")));
                    if (data.outputConsumptions.putIfAbsent(value.key(), value) != null)
                        throw new IllegalStateException("duplicate output consumption checkpoint");
                    if (value.state() == FieldworkCargo.ConsumptionState.PENDING && !unresolvedBodies.add(value.npcId()))
                        throw new IllegalStateException("multiple unresolved output consumptions for one body");
                }
                for (var body : data.outputConsumptions.values().stream().map(FieldworkCargo.OutputConsumption::npcId).distinct().toList())
                    data.pendingCargo(body); // Validate committed debits against the actual, ordered production history.
            } catch (RuntimeException exception) {
                throw new IllegalStateException("invalid output consumption checkpoint; refusing unsafe replay", exception);
            }
        }
        data.setDirty(false);
        return data;
    }

    private static List<Unreachable> sortedMemories(Map<TargetKey, Unreachable> memories) {
        return memories.values().stream().sorted(Comparator.comparingLong(Unreachable::retryAt)
                .thenComparing(Unreachable::dimension).thenComparingLong(value -> value.target().asLong())).toList();
    }

    private static CompoundTag saveArea(FieldworkSearchArea area) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", area.dimension());
        tag.putLong("Min", area.min().asLong());
        tag.putLong("Max", area.max().asLong());
        return tag;
    }

    private static FieldworkSearchArea loadArea(CompoundTag tag) {
        return new FieldworkSearchArea(tag.getString("Dimension"),
                BlockPos.of(number(tag, "Min")), BlockPos.of(number(tag, "Max")));
    }

    private static CompoundTag saveReceipt(FieldworkActionReceipt receipt) {
        FieldworkAction action = receipt.action();
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlanId", action.planId());
        tag.putUUID("NpcId", action.npcId());
        tag.putInt("Step", action.stepIndex());
        tag.putString("Dimension", action.dimension());
        tag.putLong("Target", action.target().asLong());
        tag.putString("Operation", action.operation().name());
        tag.putString("ExpectedState", action.expectedState());
        tag.putLong("CommittedAt", receipt.committedAt());
        tag.putString("ResultingState", receipt.resultingState());
        tag.putInt("ToolDamage", receipt.toolDamage());
        ListTag deltas = new ListTag();
        for (FieldworkActionReceipt.ItemDelta delta : receipt.inventoryDeltas()) {
            CompoundTag item = new CompoundTag();
            item.putString("Fingerprint", delta.fingerprint());
            item.putInt("Quantity", delta.quantity());
            deltas.add(item);
        }
        tag.put("InventoryDeltas", deltas);
        return tag;
    }

    private static FieldworkActionReceipt loadReceipt(CompoundTag tag) {
        FieldworkAction action = new FieldworkAction(uuid(tag, "PlanId"), uuid(tag, "NpcId"),
                integer(tag, "Step"), tag.getString("Dimension"), BlockPos.of(number(tag, "Target")),
                FieldworkAction.Operation.valueOf(tag.getString("Operation")), tag.getString("ExpectedState"));
        List<FieldworkActionReceipt.ItemDelta> deltas = new ArrayList<>();
        ListTag items = requireList(tag, "InventoryDeltas");
        if (items.size() > 96) throw new IllegalArgumentException("too many item deltas");
        for (Tag entry : items) {
            CompoundTag item = (CompoundTag) entry;
            deltas.add(new FieldworkActionReceipt.ItemDelta(item.getString("Fingerprint"), integer(item, "Quantity")));
        }
        return new FieldworkActionReceipt(action, number(tag, "CommittedAt"), tag.getString("ResultingState"),
                deltas, integer(tag, "ToolDamage"));
    }

    private static UUID uuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("missing UUID: " + key);
        return tag.getUUID(key);
    }

    private static long number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("missing long: " + key);
        return tag.getLong(key);
    }

    private static int integer(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("missing integer: " + key);
        return tag.getInt(key);
    }

    private static ListTag requireList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("missing list: " + key);
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        if (!((ListTag) tag.get(key)).isEmpty() && ((ListTag) tag.get(key)).getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("wrong list type: " + key);
        }
        return list;
    }
}
