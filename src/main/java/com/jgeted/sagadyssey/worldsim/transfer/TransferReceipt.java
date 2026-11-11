package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record TransferReceipt(UUID transferId, Status status, boolean lawful, String reason,
                              Map<UUID, Long> finalRevisions, long completedAt) {
    public TransferReceipt {
        finalRevisions = Map.copyOf(finalRevisions == null ? Map.of() : finalRevisions);
        reason = reason == null ? "" : reason;
    }

    public boolean successful() { return status == Status.SUCCESS; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, transferId);
        tag.putString("Status", status.name());
        tag.putBoolean("Lawful", lawful);
        tag.putString("Reason", reason);
        tag.putLong("CompletedAt", completedAt);
        ListTag revisions = new ListTag();
        finalRevisions.forEach((id, revision) -> {
            CompoundTag entry = new CompoundTag();
            entry.store("Container", UUIDUtil.CODEC, id);
            entry.putLong("Revision", revision);
            revisions.add(entry);
        });
        tag.put("Revisions", revisions);
        return tag;
    }

    public static TransferReceipt load(CompoundTag tag) {
        Map<UUID, Long> revisions = new HashMap<>();
        ListTag list = MinecraftApi.listOfType(tag, "Revisions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            if (entry.read("Container", UUIDUtil.CODEC).isPresent()) revisions.put(entry.read("Container", UUIDUtil.CODEC).orElseThrow(), entry.getLongOr("Revision", 0L));
        }
        return new TransferReceipt(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), Status.valueOf(tag.getStringOr("Status", "")),
                tag.getBooleanOr("Lawful", false), tag.getStringOr("Reason", ""), revisions,
                tag.getLongOr("CompletedAt", 0L));
    }

    public enum Status {
        SUCCESS, INVALID, ENDPOINT_MISSING, STALE_REVISION, INSUFFICIENT_ITEMS,
        RIGHTS_DENIED, RIGHTS_CONTESTED, CAPACITY_EXCEEDED, INTERNAL_FAILURE
    }
}
