package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record TransferAudit(UUID transferId, SubjectRef actor, TransferIntent intent,
                            TransferReceipt.Status status, boolean lawful, String authority,
                            String reason, int legCount, long completedAt) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", transferId);
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
        return new TransferAudit(tag.getUUID("Id"), SubjectRef.load(tag.getCompound("Actor")),
                TransferIntent.valueOf(tag.getString("Intent")),
                TransferReceipt.Status.valueOf(tag.getString("Status")),
                tag.getBoolean("Lawful"), tag.getString("Authority"), tag.getString("Reason"),
                tag.getInt("Legs"), tag.getLong("CompletedAt"));
    }
}
