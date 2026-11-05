package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded idempotency window plus durable audit trail for material transfers. */
public final class TransferLedgerData extends SavedData {
    public static final String DATA_NAME = "sagadyssey_transfer_ledger_v2";
    private static final int MAX_RECEIPTS = 1024;
    private static final int MAX_AUDITS = 2048;
    public static final Factory<TransferLedgerData> FACTORY =
            new Factory<>(TransferLedgerData::new, TransferLedgerData::load);

    private final LinkedHashMap<UUID, TransferReceipt> receipts = new LinkedHashMap<>();
    private final Deque<TransferAudit> audits = new ArrayDeque<>();
    private final Map<UUID, CompoundTag> requestIdentities = new LinkedHashMap<>();

    /** Legacy receipts lack payload evidence; keep their original replay behavior. */
    public boolean conflictsWith(TransferRequest request) {
        var identity = requestIdentities.get(request.transferId());
        return identity != null && !identity.equals(request.identity());
    }

    public void record(TransferRequest request, TransferReceipt receipt, TransferAudit audit) {
        if (!request.transferId().equals(receipt.transferId()))
            throw new IllegalArgumentException("transfer receipt identity mismatch");
        if (conflictsWith(request)) throw new IllegalStateException("transfer request identity conflict");
        // Never attach guessed payload evidence to an older, already recorded receipt.
        if (!receipts.containsKey(receipt.transferId()))
            requestIdentities.put(receipt.transferId(), request.identity());
        record(receipt, audit);
    }

    public static TransferLedgerData get(MinecraftServer server) {
        return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(server, FACTORY, DATA_NAME);
    }

    @Nullable public TransferReceipt receipt(UUID transferId) { return receipts.get(transferId); }
    /** Evidence only: never creates a receipt, changes dirtiness, or exposes mutable ledger state. */
    @Nullable public CompoundTag requestIdentity(UUID transferId) {
        CompoundTag identity = requestIdentities.get(transferId);
        return identity == null ? null : identity.copy();
    }
    public List<TransferAudit> audits() { return List.copyOf(audits); }

    public void record(TransferReceipt receipt, TransferAudit audit) {
        receipts.putIfAbsent(receipt.transferId(), receipt);
        while (receipts.size() > MAX_RECEIPTS) {
            UUID oldest = receipts.keySet().iterator().next();
            receipts.remove(oldest);
            requestIdentities.remove(oldest);
        }
        audits.addLast(audit);
        while (audits.size() > MAX_AUDITS) audits.removeFirst();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag receiptList = new ListTag();
        receipts.values().forEach(receipt -> {
            var entry = receipt.save();
            var identity = requestIdentities.get(receipt.transferId());
            if (identity != null) entry.put("RequestIdentity", identity.copy());
            receiptList.add(entry);
        });
        tag.put("Receipts", receiptList);
        ListTag auditList = new ListTag();
        audits.forEach(audit -> auditList.add(audit.save()));
        tag.put("Audits", auditList);
        return tag;
    }

    static TransferLedgerData load(CompoundTag tag, HolderLookup.Provider registries) {
        TransferLedgerData data = new TransferLedgerData();
        ListTag receipts = tag.getList("Receipts", Tag.TAG_COMPOUND);
        for (int i = 0; i < receipts.size(); i++) {
            TransferReceipt receipt = TransferReceipt.load(receipts.getCompound(i));
            data.receipts.put(receipt.transferId(), receipt);
            CompoundTag entry = receipts.getCompound(i);
            if (entry.contains("RequestIdentity")) {
                if (!entry.contains("RequestIdentity", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("invalid transfer request identity");
                var identity = entry.getCompound("RequestIdentity");
                if (identity.getInt("Version") != 1)
                    throw new IllegalArgumentException("unknown transfer request identity version");
                data.requestIdentities.put(receipt.transferId(), identity.copy());
            }
        }
        ListTag audits = tag.getList("Audits", Tag.TAG_COMPOUND);
        for (int i = 0; i < audits.size(); i++) data.audits.addLast(TransferAudit.load(audits.getCompound(i)));
        return data;
    }
}
