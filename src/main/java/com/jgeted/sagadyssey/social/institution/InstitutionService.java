package com.jgeted.sagadyssey.social.institution;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/** Establishes institutions and bootstraps their first accountable office. */
public final class InstitutionService {
    private InstitutionService() { }

    public static InstitutionRecord establish(InstitutionData data, RightsStateData rights,
                                              UUID institutionId, String name, InstitutionKind kind,
                                              @Nullable UUID settlementId, UUID treasuryContainerId,
                                              SubjectRef founder, Set<OfficeAuthority> founderPowers,
                                              long now, String charter) {
        InstitutionRecord record = new InstitutionRecord(institutionId, name, kind, settlementId,
                treasuryContainerId, now, true, charter);
        if (!data.addInstitution(record)) throw new IllegalStateException("组织标识已经存在");
        SubjectRef institution = SubjectRef.institution(institutionId);
        rights.ensureContext(treasuryContainerId, "institution_treasury:" + institutionId,
                institution, institution, AccessPolicy.INSTITUTION);
        data.addMembership(new InstitutionMembership(UUID.randomUUID(), institutionId, founder,
                MembershipRank.OFFICER, 60, now, true, "创立成员"));
        data.addOffice(new InstitutionOffice(UUID.randomUUID(), institutionId, "创立执事",
                founder, founderPowers, institution, now, 0L, false));
        return record;
    }

    public static InstitutionOffice appoint(InstitutionData data, UUID institutionId,
                                            SubjectRef actor, SubjectRef holder, String title,
                                            Set<OfficeAuthority> powers, long now, long endsAt) {
        InstitutionAuthorityVerdict verdict = data.authority(actor, institutionId,
                OfficeAuthority.APPOINT_OFFICER, now);
        if (!verdict.allowed()) throw new IllegalStateException(verdict.reason());
        InstitutionOffice office = new InstitutionOffice(UUID.randomUUID(), institutionId,
                title, holder, powers, actor, now, endsAt, false);
        if (!data.addOffice(office)) throw new IllegalStateException("无法登记职位");
        return office;
    }
}
