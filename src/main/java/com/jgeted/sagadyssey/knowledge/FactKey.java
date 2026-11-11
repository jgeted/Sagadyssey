package com.jgeted.sagadyssey.knowledge;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Subject", UUIDUtil.CODEC, subjectId);
        tag.putString("Predicate", predicate);
        tag.putString("Qualifier", qualifier);
        return tag;
    }

    public static FactKey load(CompoundTag tag) {
        return new FactKey(tag.read("Subject", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Predicate", ""),
                tag.getStringOr("Qualifier", ""));
    }
}
