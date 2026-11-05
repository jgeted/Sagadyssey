package com.jgeted.sagadyssey.social.life;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 单个行为类型的短期承诺；不同类型的决定互不阻塞。 */
public record NpcActionDecisionRecord(UUID npcId, String action, boolean accepted,
                                      @Nullable UUID subjectCharacterId,
                                      long decidedAt, long lockedUntil, String reason) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Npc", npcId);
        tag.putString("Action", action);
        tag.putBoolean("Accepted", accepted);
        if (subjectCharacterId != null) tag.putUUID("Subject", subjectCharacterId);
        tag.putLong("DecidedAt", decidedAt);
        tag.putLong("LockedUntil", lockedUntil);
        tag.putString("Reason", reason == null ? "" : reason);
        return tag;
    }

    public static NpcActionDecisionRecord load(CompoundTag tag) {
        return new NpcActionDecisionRecord(tag.getUUID("Npc"), tag.getString("Action"),
                tag.getBoolean("Accepted"), tag.hasUUID("Subject") ? tag.getUUID("Subject") : null,
                tag.getLong("DecidedAt"), tag.getLong("LockedUntil"), tag.getString("Reason"));
    }

    public boolean sameSubject(@Nullable UUID subject) {
        return java.util.Objects.equals(subjectCharacterId, subject);
    }
}
