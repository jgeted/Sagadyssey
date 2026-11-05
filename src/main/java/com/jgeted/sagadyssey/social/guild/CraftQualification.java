package com.jgeted.sagadyssey.social.guild;

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
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.put("Artisan", artisan.save());
        tag.putString("Craft", craftId);
        tag.putString("Rank", rank.name());
        ListTag evidence = new ListTag();
        evidenceIds.forEach(id -> {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", id);
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
        ListTag values = tag.getList("Evidence", Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) evidence.add(values.getCompound(index).getUUID("Id"));
        return new CraftQualification(tag.getUUID("Id"), tag.getUUID("Institution"),
                SubjectRef.load(tag.getCompound("Artisan")), tag.getString("Craft"),
                CraftRank.valueOf(tag.getString("Rank")), evidence,
                SubjectRef.load(tag.getCompound("IssuedBy")), tag.getLong("IssuedAt"),
                tag.getLong("ExpiresAt"), tag.getBoolean("Revoked"), tag.getString("Note"));
    }
}
