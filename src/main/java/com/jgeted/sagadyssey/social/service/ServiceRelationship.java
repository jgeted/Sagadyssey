package com.jgeted.sagadyssey.social.service;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record ServiceRelationship(UUID id, UUID principalId, UUID agentId, ServiceBasis basis,
                                  Set<AuthorityScope> scopes, List<ServiceObligation> obligations,
                                  ServiceLimits limits, ServiceStatus status, long startedAt,
                                  @Nullable Long endsAt, long revision, String statusReason) {
    public ServiceRelationship {
        if (id == null || principalId == null || agentId == null || principalId.equals(agentId)
                || basis == null || scopes == null || scopes.isEmpty() || limits == null
                || status == null) throw new IllegalArgumentException("invalid service relationship");
        scopes = Set.copyOf(scopes);
        obligations = List.copyOf(obligations == null ? List.of() : obligations);
        revision = Math.max(0L, revision);
        statusReason = statusReason == null ? "" : statusReason;
    }

    public boolean activeAt(long gameTime) {
        return status.grantsAuthority() && gameTime >= startedAt
                && (endsAt == null || gameTime <= endsAt);
    }

    public ServiceRelationship withStatus(ServiceStatus next, String reason, long gameTime) {
        Long ended = next.grantsAuthority() ? endsAt : gameTime;
        return new ServiceRelationship(id, principalId, agentId, basis, scopes, obligations,
                limits, next, startedAt, ended, revision + 1L, reason);
    }

    public ServiceRelationship renewed(long newEndsAt, String reason) {
        return new ServiceRelationship(id, principalId, agentId, basis, scopes, obligations,
                limits, ServiceStatus.ACTIVE, startedAt, newEndsAt, revision + 1L, reason);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Principal", UUIDUtil.CODEC, principalId);
        tag.store("Agent", UUIDUtil.CODEC, agentId);
        tag.putString("Basis", basis.name());
        ListTag scopeList = new ListTag();
        new TreeSet<>(scopes.stream().map(Enum::name).toList())
                .forEach(value -> scopeList.add(StringTag.valueOf(value)));
        tag.put("Scopes", scopeList);
        ListTag obligationList = new ListTag();
        obligations.forEach(obligation -> obligationList.add(obligation.save()));
        tag.put("Obligations", obligationList);
        tag.put("Limits", limits.save());
        tag.putString("Status", status.name());
        tag.putLong("Started", startedAt);
        if (endsAt != null) tag.putLong("Ends", endsAt);
        tag.putLong("Revision", revision);
        tag.putString("Reason", statusReason);
        return tag;
    }

    public static ServiceRelationship load(CompoundTag tag) {
        EnumSet<AuthorityScope> scopes = EnumSet.noneOf(AuthorityScope.class);
        ListTag scopeList = MinecraftApi.listOfType(tag, "Scopes", Tag.TAG_STRING);
        for (int index = 0; index < scopeList.size(); index++) {
            scopes.add(AuthorityScope.valueOf(scopeList.getStringOr(index, "")));
        }
        List<ServiceObligation> obligations = new ArrayList<>();
        ListTag obligationList = MinecraftApi.listOfType(tag, "Obligations", Tag.TAG_COMPOUND);
        for (int index = 0; index < obligationList.size(); index++) {
            obligations.add(ServiceObligation.load(obligationList.getCompoundOrEmpty(index)));
        }
        return new ServiceRelationship(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Principal", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Agent", UUIDUtil.CODEC).orElseThrow(), ServiceBasis.valueOf(tag.getStringOr("Basis", "")), scopes,
                obligations, ServiceLimits.load(tag.getCompoundOrEmpty("Limits")),
                ServiceStatus.valueOf(tag.getStringOr("Status", "")), tag.getLongOr("Started", 0L),
                tag.contains("Ends") ? tag.getLongOr("Ends", 0L) : null, tag.getLongOr("Revision", 0L),
                tag.getStringOr("Reason", ""));
    }
}
