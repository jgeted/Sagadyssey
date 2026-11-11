package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Globally typed holder reference; equal UUID values in different social domains never alias. */
public record SubjectRef(SubjectType type, UUID id) {
    public SubjectRef {
        if (type == null || id == null) throw new IllegalArgumentException("subject must be complete");
    }

    public static SubjectRef character(UUID id) { return new SubjectRef(SubjectType.CHARACTER, id); }
    public static SubjectRef household(UUID id) { return new SubjectRef(SubjectType.HOUSEHOLD, id); }
    public static SubjectRef institution(UUID id) { return new SubjectRef(SubjectType.INSTITUTION, id); }
    public static SubjectRef container(UUID id) { return new SubjectRef(SubjectType.CONTAINER, id); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        tag.store("Id", UUIDUtil.CODEC, id);
        return tag;
    }

    public static SubjectRef load(CompoundTag tag) {
        return new SubjectRef(SubjectType.valueOf(tag.getStringOr("Type", "")), tag.read("Id", UUIDUtil.CODEC).orElseThrow());
    }

    public enum SubjectType { CHARACTER, HOUSEHOLD, INSTITUTION, CONTAINER }
}
