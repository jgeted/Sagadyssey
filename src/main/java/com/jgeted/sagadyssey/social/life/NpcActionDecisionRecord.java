package com.jgeted.sagadyssey.social.life;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 单个行为类型的短期承诺；不同类型的决定互不阻塞。 */
public record NpcActionDecisionRecord(UUID npcId, String action, boolean accepted,
                                      @Nullable UUID subjectCharacterId,
                                      long decidedAt, long lockedUntil, String reason) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Npc", UUIDUtil.CODEC, npcId);
        tag.putString("Action", action);
        tag.putBoolean("Accepted", accepted);
        if (subjectCharacterId != null) tag.store("Subject", UUIDUtil.CODEC, subjectCharacterId);
        tag.putLong("DecidedAt", decidedAt);
        tag.putLong("LockedUntil", lockedUntil);
        tag.putString("Reason", reason == null ? "" : reason);
        return tag;
    }

    public static NpcActionDecisionRecord load(CompoundTag tag) {
        return new NpcActionDecisionRecord(tag.read("Npc", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Action", ""),
                tag.getBooleanOr("Accepted", false), tag.read("Subject", UUIDUtil.CODEC).isPresent() ? tag.read("Subject", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("DecidedAt", 0L), tag.getLongOr("LockedUntil", 0L), tag.getStringOr("Reason", ""));
    }

    public boolean sameSubject(@Nullable UUID subject) {
        return java.util.Objects.equals(subjectCharacterId, subject);
    }
}
