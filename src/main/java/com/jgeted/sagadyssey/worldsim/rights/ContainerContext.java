package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Legal and concurrency context for one physical container. */
public record ContainerContext(UUID containerId, String locator, SubjectRef custodian,
                               SubjectRef defaultBeneficialOwner, AccessPolicy accessPolicy,
                               long revision) {
    public ContainerContext {
        if (containerId == null || custodian == null || defaultBeneficialOwner == null
                || accessPolicy == null) throw new IllegalArgumentException("incomplete container context");
        locator = locator == null ? "" : locator;
        revision = Math.max(0L, revision);
    }

    public ContainerContext bumped() {
        return new ContainerContext(containerId, locator, custodian, defaultBeneficialOwner,
                accessPolicy, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, containerId);
        tag.putString("Locator", locator);
        tag.put("Custodian", custodian.save());
        tag.put("Owner", defaultBeneficialOwner.save());
        tag.putString("Access", accessPolicy.name());
        tag.putLong("Revision", revision);
        return tag;
    }

    public static ContainerContext load(CompoundTag tag) {
        return new ContainerContext(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Locator", ""),
                SubjectRef.load(tag.getCompoundOrEmpty("Custodian")),
                SubjectRef.load(tag.getCompoundOrEmpty("Owner")),
                AccessPolicy.valueOf(tag.getStringOr("Access", "")), tag.getLongOr("Revision", 0L));
    }
}
