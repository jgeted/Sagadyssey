package com.jgeted.sagadyssey.social.institution;

import java.util.List;
import java.util.UUID;

public record InstitutionAuthorityVerdict(boolean allowed, OfficeAuthority authority,
                                           List<UUID> officeIds, String reason) {
    public InstitutionAuthorityVerdict {
        officeIds = List.copyOf(officeIds == null ? List.of() : officeIds);
        reason = reason == null ? "" : reason;
    }
}
