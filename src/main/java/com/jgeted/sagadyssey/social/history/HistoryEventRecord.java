package com.jgeted.sagadyssey.social.history;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 可供编年史、NPC 对话和后续政治系统共同消费的结构化历史事件。 */
public record HistoryEventRecord(UUID id, long gameTime, String type,
                                 @Nullable UUID subjectId, String subjectName,
                                 String detail, int importance) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putLong("Time", gameTime);
        tag.putString("Type", type);
        if (subjectId != null) tag.putUUID("Subject", subjectId);
        tag.putString("Name", subjectName);
        tag.putString("Detail", detail);
        tag.putInt("Importance", importance);
        return tag;
    }

    public static HistoryEventRecord load(CompoundTag tag) {
        return new HistoryEventRecord(
                tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
                tag.getLong("Time"), tag.getString("Type"),
                tag.hasUUID("Subject") ? tag.getUUID("Subject") : null,
                tag.getString("Name"), tag.getString("Detail"), tag.getInt("Importance"));
    }
}
