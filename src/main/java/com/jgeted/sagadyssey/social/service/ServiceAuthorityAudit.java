package com.jgeted.sagadyssey.social.service;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record ServiceAuthorityAudit(UUID principalId, UUID agentId, AuthorityScope scope,
                                    ServiceAuthorityVerdict.Status result, String reason,
                                    long gameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Principal", principalId);
        tag.putUUID("Agent", agentId);
        tag.putString("Scope", scope.name());
        tag.putString("Result", result.name());
        tag.putString("Reason", reason);
        tag.putLong("GameTime", gameTime);
        return tag;
    }

    public static ServiceAuthorityAudit load(CompoundTag tag) {
        return new ServiceAuthorityAudit(tag.getUUID("Principal"), tag.getUUID("Agent"),
                AuthorityScope.valueOf(tag.getString("Scope")),
                ServiceAuthorityVerdict.Status.valueOf(tag.getString("Result")),
                tag.getString("Reason"), tag.getLong("GameTime"));
    }
}
