package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public record EncounterEvent(UUID id, UUID encounterId, EncounterEventType type,
                             UUID subjectCharacterId,
                             @Nullable UUID otherCharacterId,
                             @Nullable UUID sourceRecordId,
                             long occurredAt, String note) {
    public EncounterEvent {
        if (id == null || encounterId == null || type == null || subjectCharacterId == null) {
            throw new IllegalArgumentException("invalid encounter event");
        }
        note = note == null ? "" : note;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Encounter", UUIDUtil.CODEC, encounterId);
        tag.putString("Type", type.name());
        tag.store("Subject", UUIDUtil.CODEC, subjectCharacterId);
        if (otherCharacterId != null) tag.store("Other", UUIDUtil.CODEC, otherCharacterId);
        if (sourceRecordId != null) tag.store("SourceRecord", UUIDUtil.CODEC, sourceRecordId);
        tag.putLong("OccurredAt", occurredAt);
        tag.putString("Note", note);
        return tag;
    }

    static EncounterEvent load(CompoundTag tag) {
        return new EncounterEvent(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Encounter", UUIDUtil.CODEC).orElseThrow(),
                EncounterEventType.valueOf(tag.getStringOr("Type", "")), tag.read("Subject", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Other", UUIDUtil.CODEC).isPresent() ? tag.read("Other", UUIDUtil.CODEC).orElseThrow() : null,
                tag.read("SourceRecord", UUIDUtil.CODEC).isPresent() ? tag.read("SourceRecord", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("OccurredAt", 0L), tag.getStringOr("Note", ""));
    }
}
