package com.jgeted.sagadyssey.social.service;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record ServiceAuthorityAudit(UUID principalId, UUID agentId, AuthorityScope scope,
                                    ServiceAuthorityVerdict.Status result, String reason,
                                    long gameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Principal", UUIDUtil.CODEC, principalId);
        tag.store("Agent", UUIDUtil.CODEC, agentId);
        tag.putString("Scope", scope.name());
        tag.putString("Result", result.name());
        tag.putString("Reason", reason);
        tag.putLong("GameTime", gameTime);
        return tag;
    }

    public static ServiceAuthorityAudit load(CompoundTag tag) {
        return new ServiceAuthorityAudit(tag.read("Principal", UUIDUtil.CODEC).orElseThrow(), tag.read("Agent", UUIDUtil.CODEC).orElseThrow(),
                AuthorityScope.valueOf(tag.getStringOr("Scope", "")),
                ServiceAuthorityVerdict.Status.valueOf(tag.getStringOr("Result", "")),
                tag.getStringOr("Reason", ""), tag.getLongOr("GameTime", 0L));
    }
}
