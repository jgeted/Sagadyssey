package com.jgeted.sagadyssey.social.combat;

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
        tag.putUUID("Id", id);
        tag.putUUID("Encounter", encounterId);
        tag.putString("Type", type.name());
        tag.putUUID("Subject", subjectCharacterId);
        if (otherCharacterId != null) tag.putUUID("Other", otherCharacterId);
        if (sourceRecordId != null) tag.putUUID("SourceRecord", sourceRecordId);
        tag.putLong("OccurredAt", occurredAt);
        tag.putString("Note", note);
        return tag;
    }

    static EncounterEvent load(CompoundTag tag) {
        return new EncounterEvent(tag.getUUID("Id"), tag.getUUID("Encounter"),
                EncounterEventType.valueOf(tag.getString("Type")), tag.getUUID("Subject"),
                tag.hasUUID("Other") ? tag.getUUID("Other") : null,
                tag.hasUUID("SourceRecord") ? tag.getUUID("SourceRecord") : null,
                tag.getLong("OccurredAt"), tag.getString("Note"));
    }
}
