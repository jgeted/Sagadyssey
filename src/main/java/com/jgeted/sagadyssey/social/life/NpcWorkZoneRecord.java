package com.jgeted.sagadyssey.social.life;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 把原机构工作区与当前工作区分开，避免招募后继续执行已暂停的旧岗位。 */
public record NpcWorkZoneRecord(UUID npcId,
                                @Nullable BlockPos institutionMin,
                                @Nullable BlockPos institutionMax,
                                @Nullable BlockPos activeMin,
                                @Nullable BlockPos activeMax) {
    public NpcWorkZoneRecord recruited() {
        return new NpcWorkZoneRecord(npcId, institutionMin, institutionMax, null, null);
    }
    public NpcWorkZoneRecord dismissed() {
        return new NpcWorkZoneRecord(npcId, institutionMin, institutionMax,
                institutionMin, institutionMax);
    }
    public NpcWorkZoneRecord assigned(@Nullable BlockPos min, @Nullable BlockPos max) {
        return new NpcWorkZoneRecord(npcId, institutionMin, institutionMax, min, max);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Npc", npcId);
        put(tag, "InstitutionMin", institutionMin);
        put(tag, "InstitutionMax", institutionMax);
        put(tag, "ActiveMin", activeMin);
        put(tag, "ActiveMax", activeMax);
        return tag;
    }

    public static NpcWorkZoneRecord load(CompoundTag tag) {
        return new NpcWorkZoneRecord(tag.getUUID("Npc"), get(tag, "InstitutionMin"),
                get(tag, "InstitutionMax"), get(tag, "ActiveMin"), get(tag, "ActiveMax"));
    }

    private static void put(CompoundTag tag, String key, @Nullable BlockPos pos) {
        if (pos != null) tag.putLong(key, pos.asLong());
    }
    private static BlockPos get(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockPos.of(tag.getLong(key)) : null;
    }
}
