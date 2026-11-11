package com.jgeted.sagadyssey.social.commons;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Policy", UUIDUtil.CODEC, policyId);
        tag.put("Member", member.save());
        tag.putInt("Quantity", quantity);
        tag.putLong("Day", day);
        tag.putLong("WithdrawnAt", withdrawnAt);
        tag.store("Transfer", UUIDUtil.CODEC, transferId);
        return tag;
    }

    static CommonsWithdrawal load(CompoundTag tag) {
        return new CommonsWithdrawal(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Policy", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Member")), tag.getIntOr("Quantity", 0),
                tag.getLongOr("Day", 0L), tag.getLongOr("WithdrawnAt", 0L), tag.read("Transfer", UUIDUtil.CODEC).orElseThrow());
    }
}
