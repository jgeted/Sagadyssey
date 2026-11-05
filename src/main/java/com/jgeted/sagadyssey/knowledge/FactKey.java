package com.jgeted.sagadyssey.knowledge;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record FactKey(UUID subjectId, String predicate, String qualifier) {
    public FactKey {
        if (subjectId == null || predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("invalid fact key");
        }
        qualifier = qualifier == null ? "" : qualifier;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Subject", subjectId);
        tag.putString("Predicate", predicate);
        tag.putString("Qualifier", qualifier);
        return tag;
    }

    public static FactKey load(CompoundTag tag) {
        return new FactKey(tag.getUUID("Subject"), tag.getString("Predicate"),
                tag.getString("Qualifier"));
    }
}
