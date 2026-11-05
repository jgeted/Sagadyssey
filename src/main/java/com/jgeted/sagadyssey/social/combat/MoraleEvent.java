package com.jgeted.sagadyssey.social.combat;

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
        tag.putUUID("Id", id);
        tag.putUUID("Character", characterId);
        if (encounterId != null) tag.putUUID("Encounter", encounterId);
        tag.putString("Type", type.name());
        tag.putInt("Delta", delta);
        if (sourceRecordId != null) tag.putUUID("SourceRecord", sourceRecordId);
        tag.putLong("OccurredAt", occurredAt);
        tag.putString("Note", note);
        return tag;
    }

    static MoraleEvent load(CompoundTag tag) {
        return new MoraleEvent(tag.getUUID("Id"), tag.getUUID("Character"),
                tag.hasUUID("Encounter") ? tag.getUUID("Encounter") : null,
                MoraleEventType.valueOf(tag.getString("Type")), tag.getInt("Delta"),
                tag.hasUUID("SourceRecord") ? tag.getUUID("SourceRecord") : null,
                tag.getLong("OccurredAt"), tag.getString("Note"));
    }
}
