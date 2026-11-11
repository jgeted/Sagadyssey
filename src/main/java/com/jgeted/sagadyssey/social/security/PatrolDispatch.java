package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Incident", UUIDUtil.CODEC, incidentId);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
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
        ListTag guardTags = MinecraftApi.listOfType(tag, "Guards", Tag.TAG_COMPOUND);
        for (int index = 0; index < guardTags.size(); index++) {
            guards.add(SubjectRef.load(guardTags.getCompoundOrEmpty(index)));
        }
        return new PatrolDispatch(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Incident", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Institution", UUIDUtil.CODEC).orElseThrow(), SubjectRef.load(tag.getCompoundOrEmpty("DispatchedBy")),
                guards, tag.getLongOr("DispatchedAt", 0L), tag.getLongOr("ExpiresAt", 0L),
                PatrolDispatchStatus.valueOf(tag.getStringOr("Status", "")), tag.getStringOr("Orders", ""));
    }
}
