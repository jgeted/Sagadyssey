package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PatrolDispatch(UUID id, UUID incidentId, UUID institutionId,
                             SubjectRef dispatchedBy, List<SubjectRef> guards,
                             long dispatchedAt, long expiresAt,
                             PatrolDispatchStatus status, String orders) {
    public PatrolDispatch {
        if (id == null || incidentId == null || institutionId == null || dispatchedBy == null
                || guards == null || guards.isEmpty() || expiresAt <= dispatchedAt || status == null) {
            throw new IllegalArgumentException("invalid patrol dispatch");
        }
        guards = List.copyOf(guards);
        orders = orders == null ? "" : orders;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Incident", incidentId);
        tag.putUUID("Institution", institutionId);
        tag.put("DispatchedBy", dispatchedBy.save());
        ListTag guardTags = new ListTag();
        guards.forEach(guard -> guardTags.add(guard.save()));
        tag.put("Guards", guardTags);
        tag.putLong("DispatchedAt", dispatchedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Status", status.name());
        tag.putString("Orders", orders);
        return tag;
    }

    static PatrolDispatch load(CompoundTag tag) {
        List<SubjectRef> guards = new ArrayList<>();
        ListTag guardTags = tag.getList("Guards", Tag.TAG_COMPOUND);
        for (int index = 0; index < guardTags.size(); index++) {
            guards.add(SubjectRef.load(guardTags.getCompound(index)));
        }
        return new PatrolDispatch(tag.getUUID("Id"), tag.getUUID("Incident"),
                tag.getUUID("Institution"), SubjectRef.load(tag.getCompound("DispatchedBy")),
                guards, tag.getLong("DispatchedAt"), tag.getLong("ExpiresAt"),
                PatrolDispatchStatus.valueOf(tag.getString("Status")), tag.getString("Orders"));
    }
}
