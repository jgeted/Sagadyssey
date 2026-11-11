package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Time-bounded craft competence backed by named production, appraisal, or examination evidence. */
public record CraftQualification(UUID id, UUID institutionId, SubjectRef artisan,
                                 String craftId, CraftRank rank, List<UUID> evidenceIds,
                                 SubjectRef issuedBy, long issuedAt, long expiresAt,
                                 boolean revoked, String note) {
    public CraftQualification {
        if (id == null || institutionId == null || artisan == null || craftId == null
                || craftId.isBlank() || rank == null || evidenceIds == null || evidenceIds.isEmpty()
                || issuedBy == null || expiresAt != 0L && expiresAt <= issuedAt) {
            throw new IllegalArgumentException("invalid craft qualification");
        }
        craftId = craftId.trim().toLowerCase(java.util.Locale.ROOT);
        evidenceIds = List.copyOf(evidenceIds);
        note = note == null ? "" : note;
    }

    public boolean activeAt(long now) { return !revoked && now >= issuedAt && (expiresAt == 0L || now < expiresAt); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.put("Artisan", artisan.save());
        tag.putString("Craft", craftId);
        tag.putString("Rank", rank.name());
        ListTag evidence = new ListTag();
        evidenceIds.forEach(id -> {
            CompoundTag value = new CompoundTag();
            value.store("Id", UUIDUtil.CODEC, id);
            evidence.add(value);
        });
        tag.put("Evidence", evidence);
        tag.put("IssuedBy", issuedBy.save());
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putBoolean("Revoked", revoked);
        tag.putString("Note", note);
        return tag;
    }

    static CraftQualification load(CompoundTag tag) {
        List<UUID> evidence = new ArrayList<>();
        ListTag values = MinecraftApi.listOfType(tag, "Evidence", Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) evidence.add(values.getCompoundOrEmpty(index).read("Id", UUIDUtil.CODEC).orElseThrow());
        return new CraftQualification(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Artisan")), tag.getStringOr("Craft", ""),
                CraftRank.valueOf(tag.getStringOr("Rank", "")), evidence,
                SubjectRef.load(tag.getCompoundOrEmpty("IssuedBy")), tag.getLongOr("IssuedAt", 0L),
                tag.getLongOr("ExpiresAt", 0L), tag.getBooleanOr("Revoked", false), tag.getStringOr("Note", ""));
    }
}
