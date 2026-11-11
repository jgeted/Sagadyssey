package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Server truth only. This ledger neither discovers stock nor grants authority to take it. */
public final class NeedRequestData extends NbtSavedData {
    public static final String NAME = "sagadyssey_need_requests_v3";
    public static final int MAX_REQUESTS = 4_096;
    public static final int MAX_RECEIPTS = 65_536;
    public static final int MAX_DEPENDENCIES = 64;
    public static final Factory<NeedRequestData> FACTORY = new Factory<>(NeedRequestData::new, NeedRequestData::load);
    private final Map<UUID, NeedRequest> requests = new LinkedHashMap<>();
    private final Map<NeedRequest.Key, UUID> keys = new HashMap<>();
    private final Map<UUID, Set<UUID>> dependencies = new HashMap<>();
    private final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    public enum Action { RESERVE, DISPATCH, DELIVER, RELEASE_RESERVED }
    private record Receipt(UUID request, Action action, int quantity) {}

    public static NeedRequestData get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        var data = storage.get(FACTORY.type(NAME));
        if (data != null) return data;
        if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat")))
            throw new IllegalStateException("unreadable demand ledger; manual recovery required");
        data = new NeedRequestData(); storage.set(FACTORY.type(NAME), data); return data;
    }

    public synchronized NeedRequest openOrUpdate(NeedRequest.Key key, int quantity, int maxAttempts, long now) {
        UUID existing = keys.get(key);
        if (existing != null) {
            var current = require(existing);
            if (current.status().terminal() || current.status() == NeedRequestStatus.DELIVERED) {
                if (quantity != current.requested()) throw new IllegalStateException("closed demand identity cannot change");
                return current;
            }
            if (quantity == current.requested()) {
                if (now < current.updatedAt()) throw new IllegalArgumentException("demand clock moved backwards");
                return current;
            }
            return put(current.resize(quantity, now));
        }
        if (requests.size() >= MAX_REQUESTS) throw new IllegalStateException("demand ledger full; archiving required");
        var request = NeedRequest.open(key, quantity, maxAttempts, now);
        keys.put(key, request.id()); return put(request);
    }

    /** Reconcile a measured stock deficit without inventing deliveries or erasing custody.
     * Existing reservations and carried goods remain committed; only unmatched demand shrinks.
     * A cancelled request retains all historical receipts and delivered quantities.
     */
    public synchronized NeedRequest reconcileStockDeficit(UUID id, int deficit, long now) {
        if (deficit < 0) throw new IllegalArgumentException("negative stock deficit");
        var current = require(id);
        if (now < current.updatedAt()) throw new IllegalArgumentException("demand clock moved backwards");
        if (current.status().terminal() || current.status() == NeedRequestStatus.DELIVERED)
            return current;
        int committed = Math.addExact(current.reserved(), current.inTransit());
        if (deficit == 0 && committed == 0)
            return put(current.transition(NeedRequestStatus.CANCELLED, now,
                    "储备实物已满足，取消剩余缺口；保留历史交付回执"));
        int quantity = Math.addExact(current.delivered(), Math.max(deficit, committed));
        return quantity == current.requested() ? current : put(current.resize(quantity, now));
    }

    public synchronized NeedRequest request(UUID id) { return require(id); }
    public synchronized List<NeedRequest> requests() { return List.copyOf(requests.values()); }
    public synchronized NeedRequest transition(UUID id, NeedRequestStatus next, long now, String reason) {
        return put(require(id).transition(next, now, reason));
    }
    public synchronized NeedRequest failedAttempt(UUID id, long now, String reason) {
        return put(require(id).failedAttempt(now, reason));
    }

    /** Call only with an acknowledged transaction receipt; this does not perform an asset transfer. */
    public synchronized NeedRequest acknowledge(UUID receiptId, UUID requestId, Action action, int quantity, long now) {
        return acknowledgeAndPublish(receiptId, requestId, action, quantity, now, () -> {});
    }

    static UUID emergencyRouteId(UUID requestId) {
        return SupplyFlowService.receipt(requestId, "natural-v3-emergency-route");
    }
    private static UUID emergencyReceiptId(UUID requestId) {
        return SupplyFlowService.receipt(emergencyRouteId(requestId), "reserve");
    }
    /** A real reserve receipt consumes the one opportunity, even after cancellation or delivery. */
    synchronized boolean emergencyReserved(UUID requestId) {
        return emergencyReservedQuantity(requestId) > 0;
    }
    synchronized int emergencyReservedQuantity(UUID requestId) {
        require(requestId);
        var receipt = receipts.get(emergencyReceiptId(requestId));
        if (receipt != null && (!receipt.request().equals(requestId) || receipt.action() != Action.RESERVE))
            throw new IllegalStateException("emergency reserve receipt identity conflict");
        return receipt == null ? 0 : receipt.quantity();
    }
    synchronized NeedRequest acknowledgeEmergencyReserveAndPublish(UUID requestId, int quantity,
                                                                    long now, Runnable publisher) {
        Objects.requireNonNull(publisher);
        UUID receiptId = emergencyReceiptId(requestId);
        var receipt = new Receipt(requestId, Action.RESERVE, quantity);
        var previous = receipts.get(receiptId);
        if (previous != null) {
            if (!previous.equals(receipt)) throw new IllegalStateException("emergency reserve receipt identity conflict");
            return require(requestId);
        }
        if (receipts.size() >= MAX_RECEIPTS) throw new IllegalStateException("demand receipt ledger full; archiving required");
        var next = require(requestId).reserveNaturalFoodEmergency(quantity, now);
        publisher.run();
        receipts.put(receiptId, receipt); return put(next);
    }

    /**
     * Validate before publishing linked records on the server thread. The publisher must
     * perform its own fallible checks before changing state, and must not reenter this ledger.
     * No compensating receipt is needed when validation/publication fails.
     */
    synchronized NeedRequest acknowledgeAndPublish(UUID receiptId, UUID requestId, Action action,
                                                    int quantity, long now, Runnable publisher) {
        Objects.requireNonNull(receiptId); Objects.requireNonNull(action);
        Objects.requireNonNull(publisher);
        var receipt = new Receipt(requestId, action, quantity);
        var previous = receipts.get(receiptId);
        if (previous != null) {
            if (!previous.equals(receipt)) throw new IllegalStateException("asset receipt identity conflict");
            return require(requestId);
        }
        if (receipts.size() >= MAX_RECEIPTS) throw new IllegalStateException("demand receipt ledger full; archiving required");
        var current = require(requestId);
        var next = switch (action) {
            case RESERVE -> current.reserve(quantity, now);
            case DISPATCH -> current.dispatch(quantity, now);
            case DELIVER -> current.deliver(quantity, now);
            case RELEASE_RESERVED -> current.releaseReserved(quantity, now);
        };
        publisher.run();
        receipts.put(receiptId, receipt); return put(next);
    }

    /** Read-only identity check; missing evidence is not a successful receipt. */
    synchronized boolean hasReceipt(UUID id, UUID request, Action action, int quantity) {
        var previous = receipts.get(id);
        if (previous == null) return false;
        if (!previous.equals(new Receipt(request, action, quantity)))
            throw new IllegalStateException("asset receipt identity conflict");
        return true;
    }

    /** A copy, used to detect orphaned reserve receipts before considering another route. */
    synchronized Map<UUID, Integer> reserveReceipts(UUID request) {
        require(request);
        var result = new LinkedHashMap<UUID, Integer>();
        receipts.forEach((id, r) -> { if (r.request().equals(request) && r.action() == Action.RESERVE)
            result.put(id, r.quantity()); });
        return Map.copyOf(result);
    }

    synchronized int acknowledgedQuantity(UUID request, Action action) {
        require(request);
        return Math.toIntExact(receipts.values().stream().filter(r -> r.request().equals(request) && r.action() == action)
                .mapToLong(Receipt::quantity).sum());
    }

    synchronized NeedRequest acknowledgeExistingStockReserveAndPublish(UUID receiptId, UUID requestId,
                                                                         int quantity, long now, Runnable publisher) {
        Objects.requireNonNull(receiptId); Objects.requireNonNull(publisher);
        if (hasReceipt(receiptId, requestId, Action.RESERVE, quantity)) return require(requestId);
        if (receipts.size() >= MAX_RECEIPTS) throw new IllegalStateException("demand receipt ledger full; archiving required");
        var next = require(requestId).reserveExistingStock(quantity, now);
        publisher.run();
        receipts.put(receiptId, new Receipt(requestId, Action.RESERVE, quantity));
        return put(next);
    }

    /** Cyclic dependencies never get installed, and block further matching. */
    public synchronized boolean dependOn(UUID requestId, UUID prerequisite, long now) {
        var request = require(requestId); require(prerequisite);
        if (request.status().terminal() || request.status() == NeedRequestStatus.DELIVERED)
            throw new IllegalStateException("settled demand cannot acquire dependencies");
        if (now < request.updatedAt()) throw new IllegalArgumentException("dependency clock moved backwards");
        if (request.reserved() > 0 || request.inTransit() > 0)
            throw new IllegalStateException("dependencies must be validated before committing assets");
        if (requestId.equals(prerequisite) || reaches(prerequisite, requestId)) {
            if (request.status() != NeedRequestStatus.BLOCKED_CYCLE)
                put(request.transition(NeedRequestStatus.BLOCKED_CYCLE, now, "dependency cycle"));
            return false;
        }
        var linked = dependencies.computeIfAbsent(requestId, ignored -> new HashSet<>());
        if (!linked.contains(prerequisite) && linked.size() >= MAX_DEPENDENCIES)
            throw new IllegalStateException("demand dependency limit reached");
        if (linked.add(prerequisite)) setDirty();
        return true;
    }
    public synchronized Set<UUID> dependencies(UUID id) { require(id); return Set.copyOf(dependencies.getOrDefault(id, Set.of())); }
    private boolean reaches(UUID from, UUID target) {
        var visited = new HashSet<UUID>(); var queue = new ArrayDeque<UUID>(); queue.add(from);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (current.equals(target)) return true;
            if (visited.add(current)) queue.addAll(dependencies.getOrDefault(current, Set.of()));
        }
        return false;
    }
    private NeedRequest require(UUID id) {
        var request = requests.get(id);
        if (request == null) throw new NoSuchElementException("unknown demand " + id);
        return request;
    }
    private NeedRequest put(NeedRequest request) { requests.put(request.id(), request); setDirty(); return request; }

    @Override public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("Schema", 1);
        var entries = new ListTag(); requests.values().forEach(r -> entries.add(r.save())); tag.put("Requests", entries);
        var edges = new ListTag(); dependencies.forEach((id, linked) -> linked.forEach(to -> {
            var edge = new CompoundTag(); edge.store("From", UUIDUtil.CODEC, id); edge.store("To", UUIDUtil.CODEC, to); edges.add(edge);
        })); tag.put("Dependencies", edges);
        var journal = new ListTag(); receipts.forEach((id, receipt) -> {
            var entry = new CompoundTag(); entry.store("Id", UUIDUtil.CODEC, id); entry.store("Request", UUIDUtil.CODEC, receipt.request());
            entry.putString("Action", receipt.action().name()); entry.putInt("Quantity", receipt.quantity()); journal.add(entry);
        }); tag.put("Receipts", journal); return tag;
    }

    public static NeedRequestData load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!MinecraftApi.containsTag(tag, "Schema", Tag.TAG_INT) || tag.getIntOr("Schema", 0) != 1)
            throw new IllegalArgumentException("unknown demand ledger schema");
        for (String name : List.of("Requests", "Dependencies", "Receipts"))
            if (!(tag.get(name) instanceof ListTag list) || (!list.isEmpty() && MinecraftApi.elementType(list) != Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("invalid demand ledger list " + name);
        var data = new NeedRequestData();
        for (Tag value : MinecraftApi.listOfType(tag, "Requests", Tag.TAG_COMPOUND)) {
            var request = NeedRequest.load((CompoundTag) value);
            if (data.requests.size() >= MAX_REQUESTS || data.requests.putIfAbsent(request.id(), request) != null
                    || data.keys.putIfAbsent(request.key(), request.id()) != null)
                throw new IllegalArgumentException("duplicate or excessive demand identity");
        }
        for (Tag value : MinecraftApi.listOfType(tag, "Dependencies", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            if (!entry.read("From", UUIDUtil.CODEC).isPresent() || !entry.read("To", UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("invalid dependency");
            UUID from = entry.read("From", UUIDUtil.CODEC).orElseThrow(), to = entry.read("To", UUIDUtil.CODEC).orElseThrow(); data.require(from); data.require(to);
            if (from.equals(to) || data.reaches(to, from)
                    || data.dependencies.getOrDefault(from, Set.of()).size() >= MAX_DEPENDENCIES
                    || !data.dependencies.computeIfAbsent(from, ignored -> new HashSet<>()).add(to))
                throw new IllegalArgumentException("cyclic or duplicate demand dependency");
        }
        for (Tag value : MinecraftApi.listOfType(tag, "Receipts", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            if (!entry.read("Id", UUIDUtil.CODEC).isPresent() || !entry.read("Request", UUIDUtil.CODEC).isPresent() || !MinecraftApi.containsTag(entry, "Action", Tag.TAG_STRING)
                    || !MinecraftApi.containsTag(entry, "Quantity", Tag.TAG_INT) || entry.getIntOr("Quantity", 0) <= 0)
                throw new IllegalArgumentException("invalid demand receipt");
            data.require(entry.read("Request", UUIDUtil.CODEC).orElseThrow());
            var receipt = new Receipt(entry.read("Request", UUIDUtil.CODEC).orElseThrow(), Action.valueOf(entry.getStringOr("Action", "")), entry.getIntOr("Quantity", 0));
            if (data.receipts.size() >= MAX_RECEIPTS || data.receipts.putIfAbsent(entry.read("Id", UUIDUtil.CODEC).orElseThrow(), receipt) != null)
                throw new IllegalArgumentException("duplicate or excessive demand receipt");
        }
        return data;
    }
}
