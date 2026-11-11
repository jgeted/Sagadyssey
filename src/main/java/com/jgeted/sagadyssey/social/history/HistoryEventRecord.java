package com.jgeted.sagadyssey.social.history;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 可供编年史、NPC 对话和后续政治系统共同消费的结构化历史事件。 */
public record HistoryEventRecord(UUID id, long gameTime, String type,
                                 @Nullable UUID subjectId, String subjectName,
                                 String detail, int importance) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putLong("Time", gameTime);
        tag.putString("Type", type);
        if (subjectId != null) tag.store("Subject", UUIDUtil.CODEC, subjectId);
        tag.putString("Name", subjectName);
        tag.putString("Detail", detail);
        tag.putInt("Importance", importance);
        return tag;
    }

    public static HistoryEventRecord load(CompoundTag tag) {
        return new HistoryEventRecord(
                tag.read("Id", UUIDUtil.CODEC).isPresent() ? tag.read("Id", UUIDUtil.CODEC).orElseThrow() : UUID.randomUUID(),
                tag.getLongOr("Time", 0L), tag.getStringOr("Type", ""),
                tag.read("Subject", UUIDUtil.CODEC).isPresent() ? tag.read("Subject", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getStringOr("Name", ""), tag.getStringOr("Detail", ""), tag.getIntOr("Importance", 0));
    }
}
