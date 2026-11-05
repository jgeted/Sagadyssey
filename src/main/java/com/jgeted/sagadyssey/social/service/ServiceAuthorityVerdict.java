package com.jgeted.sagadyssey.social.service;

import java.util.List;
import java.util.UUID;

public record ServiceAuthorityVerdict(Status status, AuthorityScope scope,
                                      List<UUID> relationshipIds, String reason) {
    public ServiceAuthorityVerdict {
        relationshipIds = List.copyOf(relationshipIds == null ? List.of() : relationshipIds);
        reason = reason == null ? "" : reason;
    }

    public boolean allowed() { return status == Status.ALLOW; }
    public enum Status { ALLOW, DENY, REFUSED }
}
