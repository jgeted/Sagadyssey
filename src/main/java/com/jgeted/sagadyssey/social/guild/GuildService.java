package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.social.market.AppraisalData;
import com.jgeted.sagadyssey.social.market.AppraisalRecord;
import com.jgeted.sagadyssey.social.market.QualityGrade;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.Container;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class GuildService {
    private GuildService() { }

    public static CraftQualification certify(InstitutionData institutions, GuildData guilds,
                                             UUID institutionId, SubjectRef certifier,
                                             SubjectRef artisan, String craftId, CraftRank rank,
                                             List<UUID> evidenceIds, long now, long expiresAt,
                                             String note) {
        var authority = institutions.authority(certifier, institutionId,
                OfficeAuthority.CERTIFY_CRAFT, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        if (institutions.membership(institutionId, artisan) == null) {
            throw new IllegalStateException("工匠不是该行会的有效成员");
        }
        CraftQualification qualification = new CraftQualification(UUID.randomUUID(),
                institutionId, artisan, craftId, rank, evidenceIds, certifier,
                now, expiresAt, false, note);
        if (!guilds.addQualification(qualification)) throw new IllegalStateException("资质已经存在");
        return qualification;
    }

    public static GuildQualityStandard adoptStandard(InstitutionData institutions,
                                                     GuildData guilds, UUID institutionId,
                                                     SubjectRef certifier, String craftId,
                                                     ItemFingerprint product,
                                                     Set<QualityGrade> acceptedGrades,
                                                     long now, String description) {
        var authority = institutions.authority(certifier, institutionId,
                OfficeAuthority.CERTIFY_CRAFT, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        GuildQualityStandard standard = new GuildQualityStandard(UUID.randomUUID(),
                institutionId, craftId, product, acceptedGrades, now, true, description);
        if (!guilds.addStandard(standard)) throw new IllegalStateException("标准已经存在");
        return standard;
    }

    public static GuildInspection inspect(GuildData guilds, AppraisalData appraisals,
                                          UUID standardId, SubjectRef inspector,
                                          UUID appraisalId, UUID containerId,
                                          Container container, int slot, long now) {
        GuildQualityStandard standard = guilds.standard(standardId);
        if (standard == null || !standard.active()) throw new IllegalStateException("行会标准无效");
        CraftQualification qualification = guilds.qualification(standard.institutionId(),
                inspector, standard.craftId(), now);
        if (qualification == null) throw new IllegalStateException("检验人没有有效工艺资质");
        AppraisalRecord appraisal = appraisals.byId(appraisalId);
        if (appraisal == null || !appraisal.appraiser().equals(inspector)
                || !appraisal.verifies(containerId, slot, container, now)) {
            throw new IllegalStateException("鉴定证据不存在、已过期或物品已经变化");
        }
        boolean passed = standard.product().equals(appraisal.fingerprint())
                && standard.acceptedGrades().contains(appraisal.grade());
        GuildInspection inspection = new GuildInspection(UUID.randomUUID(),
                standard.institutionId(), standard.id(), qualification.id(), appraisal.markId(),
                inspector, passed, now, passed ? "符合行会公开标准" : "未达到行会公开标准");
        guilds.addInspection(inspection);
        return inspection;
    }
}
