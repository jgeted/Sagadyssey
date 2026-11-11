package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public record MoraleEvent(UUID id, UUID characterId, @Nullable UUID encounterId,
                          MoraleEventType type, int delta,
                          @Nullable UUID sourceRecordId, long occurredAt, String note) {
    public MoraleEvent {
        if (id == null || characterId == null || type == null || delta < -100 || delta > 100) {
            throw new IllegalArgumentException("invalid morale event");
        }
        note = note == null ? "" : note;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Character", UUIDUtil.CODEC, characterId);
        if (encounterId != null) tag.store("Encounter", UUIDUtil.CODEC, encounterId);
        tag.putString("Type", type.name());
        tag.putInt("Delta", delta);
        if (sourceRecordId != null) tag.store("SourceRecord", UUIDUtil.CODEC, sourceRecordId);
        tag.putLong("OccurredAt", occurredAt);
        tag.putString("Note", note);
        return tag;
    }

    static MoraleEvent load(CompoundTag tag) {
        return new MoraleEvent(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Character", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Encounter", UUIDUtil.CODEC).isPresent() ? tag.read("Encounter", UUIDUtil.CODEC).orElseThrow() : null,
                MoraleEventType.valueOf(tag.getStringOr("Type", "")), tag.getIntOr("Delta", 0),
                tag.read("SourceRecord", UUIDUtil.CODEC).isPresent() ? tag.read("SourceRecord", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("OccurredAt", 0L), tag.getStringOr("Note", ""));
    }
}
