package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record TransferAudit(UUID transferId, SubjectRef actor, TransferIntent intent,
                            TransferReceipt.Status status, boolean lawful, String authority,
                            String reason, int legCount, long completedAt) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, transferId);
        tag.put("Actor", actor.save());
        tag.putString("Intent", intent.name());
        tag.putString("Status", status.name());
        tag.putBoolean("Lawful", lawful);
        tag.putString("Authority", authority);
        tag.putString("Reason", reason);
        tag.putInt("Legs", legCount);
        tag.putLong("CompletedAt", completedAt);
        return tag;
    }

    public static TransferAudit load(CompoundTag tag) {
        return new TransferAudit(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), SubjectRef.load(tag.getCompoundOrEmpty("Actor")),
                TransferIntent.valueOf(tag.getStringOr("Intent", "")),
                TransferReceipt.Status.valueOf(tag.getStringOr("Status", "")),
                tag.getBooleanOr("Lawful", false), tag.getStringOr("Authority", ""), tag.getStringOr("Reason", ""),
                tag.getIntOr("Legs", 0), tag.getLongOr("CompletedAt", 0L));
    }
}
