package com.jgeted.sagadyssey.social.life;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Npc", UUIDUtil.CODEC, npcId);
        tag.putString("Activity", activity.name());
        tag.putString("Reason", reason);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("ReviewAt", reviewAt);
        return tag;
    }

    public static NpcScheduleRecord load(CompoundTag tag) {
        return new NpcScheduleRecord(tag.read("Npc", UUIDUtil.CODEC).orElseThrow(),
                NpcScheduleActivity.parse(tag.getStringOr("Activity", "")),
                tag.getStringOr("Reason", ""), tag.getLongOr("UpdatedAt", 0L), tag.getLongOr("ReviewAt", 0L));
    }
}
