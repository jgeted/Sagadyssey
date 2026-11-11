package com.jgeted.sagadyssey.social.institution;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Revocable office assignment; authority ends with the term instead of following the holder. */
public record InstitutionOffice(UUID id, UUID institutionId, String title,
                                SubjectRef holder, Set<OfficeAuthority> authorities,
                                SubjectRef appointedBy, long startsAt, long endsAt,
                                boolean revoked) {
    public InstitutionOffice {
        if (id == null || institutionId == null || holder == null || appointedBy == null
                || authorities == null || authorities.isEmpty()
                || endsAt != 0L && endsAt <= startsAt) {
            throw new IllegalArgumentException("invalid institution office");
        }
        title = title == null || title.isBlank() ? "职员" : title.trim();
        authorities = Set.copyOf(authorities);
    }

    public boolean activeAt(long now) { return !revoked && now >= startsAt && (endsAt == 0L || now < endsAt); }

    public InstitutionOffice revoke() {
        return new InstitutionOffice(id, institutionId, title, holder, authorities,
                appointedBy, startsAt, endsAt, true);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.putString("Title", title);
        tag.put("Holder", holder.save());
        ListTag powers = new ListTag();
        authorities.forEach(power -> {
            CompoundTag value = new CompoundTag();
            value.putString("Value", power.name());
            powers.add(value);
        });
        tag.put("Authorities", powers);
        tag.put("AppointedBy", appointedBy.save());
        tag.putLong("StartsAt", startsAt);
        tag.putLong("EndsAt", endsAt);
        tag.putBoolean("Revoked", revoked);
        return tag;
    }

    static InstitutionOffice load(CompoundTag tag) {
        Set<OfficeAuthority> powers = new HashSet<>();
        ListTag values = MinecraftApi.listOfType(tag, "Authorities", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) {
            try { powers.add(OfficeAuthority.valueOf(values.getCompoundOrEmpty(index).getStringOr("Value", ""))); }
            catch (IllegalArgumentException ignored) { }
        }
        return new InstitutionOffice(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                tag.getStringOr("Title", ""), SubjectRef.load(tag.getCompoundOrEmpty("Holder")), powers,
                SubjectRef.load(tag.getCompoundOrEmpty("AppointedBy")), tag.getLongOr("StartsAt", 0L),
                tag.getLongOr("EndsAt", 0L), tag.getBooleanOr("Revoked", false));
    }
}
