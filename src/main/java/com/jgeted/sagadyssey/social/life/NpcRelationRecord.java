package com.jgeted.sagadyssey.social.life;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** NPC 对另一个社会人物的主观关系，强度范围 -100..100。 */
public record NpcRelationRecord(UUID otherCharacterId, NpcRelationKind kind,
                                int strength, String note) {
    public NpcRelationRecord {
        kind = kind == null ? NpcRelationKind.FRIEND : kind;
        strength = Math.max(-100, Math.min(100, strength));
        note = note == null ? "" : note;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Other", otherCharacterId);
        tag.putString("Kind", kind.name());
        tag.putInt("Strength", strength);
        tag.putString("Note", note);
        return tag;
    }

    public static NpcRelationRecord load(CompoundTag tag) {
        return new NpcRelationRecord(tag.getUUID("Other"),
                NpcRelationKind.parse(tag.getString("Kind")), tag.getInt("Strength"),
                tag.getString("Note"));
    }
}
