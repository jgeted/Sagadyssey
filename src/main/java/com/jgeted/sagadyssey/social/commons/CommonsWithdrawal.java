package com.jgeted.sagadyssey.social.commons;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record CommonsWithdrawal(UUID id, UUID policyId, SubjectRef member,
                                int quantity, long day, long withdrawnAt, UUID transferId) {
    public CommonsWithdrawal {
        if (id == null || policyId == null || member == null || quantity <= 0 || transferId == null) {
            throw new IllegalArgumentException("invalid commons withdrawal");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Policy", policyId);
        tag.put("Member", member.save());
        tag.putInt("Quantity", quantity);
        tag.putLong("Day", day);
        tag.putLong("WithdrawnAt", withdrawnAt);
        tag.putUUID("Transfer", transferId);
        return tag;
    }

    static CommonsWithdrawal load(CompoundTag tag) {
        return new CommonsWithdrawal(tag.getUUID("Id"), tag.getUUID("Policy"),
                SubjectRef.load(tag.getCompound("Member")), tag.getInt("Quantity"),
                tag.getLong("Day"), tag.getLong("WithdrawnAt"), tag.getUUID("Transfer"));
    }
}
