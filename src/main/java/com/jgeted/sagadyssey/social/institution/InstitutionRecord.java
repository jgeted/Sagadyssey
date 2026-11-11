package com.jgeted.sagadyssey.social.institution;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** A persistent social body with a physical treasury container and an explicit charter. */
public record InstitutionRecord(UUID id, String name, InstitutionKind kind,
                                @Nullable UUID settlementId, UUID treasuryContainerId,
                                long foundedAt, boolean active, String charter) {
    public InstitutionRecord {
        if (id == null || kind == null || treasuryContainerId == null) {
            throw new IllegalArgumentException("incomplete institution");
        }
        name = clean(name);
        charter = charter == null ? "" : charter.trim();
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "未命名组织";
        String result = value.replaceAll("§[0-9a-fk-or]", "").trim();
        return result.length() > 40 ? result.substring(0, 40) : result;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putString("Name", name);
        tag.putString("Kind", kind.name());
        if (settlementId != null) tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.store("Treasury", UUIDUtil.CODEC, treasuryContainerId);
        tag.putLong("FoundedAt", foundedAt);
        tag.putBoolean("Active", active);
        tag.putString("Charter", charter);
        return tag;
    }

    static InstitutionRecord load(CompoundTag tag) {
        return new InstitutionRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Name", ""),
                InstitutionKind.valueOf(tag.getStringOr("Kind", "")),
                tag.read("Settlement", UUIDUtil.CODEC).isPresent() ? tag.read("Settlement", UUIDUtil.CODEC).orElseThrow() : null,
                tag.read("Treasury", UUIDUtil.CODEC).orElseThrow(), tag.getLongOr("FoundedAt", 0L),
                tag.getBooleanOr("Active", false), tag.getStringOr("Charter", ""));
    }
}
