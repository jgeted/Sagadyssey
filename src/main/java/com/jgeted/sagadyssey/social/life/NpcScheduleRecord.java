package com.jgeted.sagadyssey.social.life;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 可持久查询的 NPC 日程快照。 */
public record NpcScheduleRecord(UUID npcId, NpcScheduleActivity activity,
                                String reason, long updatedAt, long reviewAt) {
    public NpcScheduleRecord {
        activity = activity == null ? NpcScheduleActivity.PERSONAL : activity;
        reason = reason == null ? "尚未评估" : reason;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Npc", npcId);
        tag.putString("Activity", activity.name());
        tag.putString("Reason", reason);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("ReviewAt", reviewAt);
        return tag;
    }

    public static NpcScheduleRecord load(CompoundTag tag) {
        return new NpcScheduleRecord(tag.getUUID("Npc"),
                NpcScheduleActivity.parse(tag.getString("Activity")),
                tag.getString("Reason"), tag.getLong("UpdatedAt"), tag.getLong("ReviewAt"));
    }
}
