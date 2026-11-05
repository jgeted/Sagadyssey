package com.jgeted.sagadyssey.social.institution;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InstitutionDataTest {
    @Test
    void officesGrantNarrowExpiringAuthorityAndTreasuryBelongsToInstitution() {
        InstitutionData data = new InstitutionData();
        RightsStateData rights = new RightsStateData();
        UUID institutionId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        UUID treasuryId = UUID.randomUUID();
        SubjectRef founder = SubjectRef.character(UUID.randomUUID());
        SubjectRef collector = SubjectRef.character(UUID.randomUUID());
        InstitutionRecord council = InstitutionService.establish(data, rights, institutionId,
                "河湾议会", InstitutionKind.SETTLEMENT_COUNCIL, settlementId, treasuryId,
                founder, Set.of(OfficeAuthority.APPOINT_OFFICER,
                        OfficeAuthority.ASSESS_TAX), 10L, "由各户推举执事");

        assertEquals(AccessPolicy.INSTITUTION, rights.context(treasuryId).accessPolicy());
        assertEquals(SubjectRef.institution(institutionId),
                rights.context(treasuryId).defaultBeneficialOwner());
        assertTrue(data.authority(founder, institutionId,
                OfficeAuthority.ASSESS_TAX, 11L).allowed());
        assertFalse(data.authority(founder, institutionId,
                OfficeAuthority.MANAGE_TREASURY, 11L).allowed());

        InstitutionOffice office = InstitutionService.appoint(data, council.id(), founder,
                collector, "收税人", Set.of(OfficeAuthority.COLLECT_TAX),
                20L, 100L);
        assertTrue(data.authority(collector, council.id(),
                OfficeAuthority.COLLECT_TAX, 99L).allowed());
        assertFalse(data.authority(collector, council.id(),
                OfficeAuthority.COLLECT_TAX, 100L).allowed());
        assertEquals(office, InstitutionOffice.load(office.save()));
    }

    @Test
    void vassalageHasInverseDirectionAndEqualClaimsCreateRealJurisdictionConflict() {
        InstitutionData data = new InstitutionData();
        RightsStateData rights = new RightsStateData();
        SubjectRef founder = SubjectRef.character(UUID.randomUUID());
        UUID settlement = UUID.randomUUID();
        InstitutionRecord lord = establish(data, rights, founder, settlement, "领主家政");
        InstitutionRecord council = establish(data, rights,
                SubjectRef.character(UUID.randomUUID()), settlement, "镇民议会");
        data.setRelation(new InstitutionRelation(council.id(), lord.id(),
                InstitutionRelationKind.VASSALAGE, 0L, 0L, "保护与地租契约"));

        assertEquals(InstitutionRelationKind.VASSALAGE,
                data.relation(council.id(), lord.id(), 1L).kind());
        assertEquals(InstitutionRelationKind.OVERLORD,
                data.relation(lord.id(), council.id(), 1L).kind());
        data.addClaim(new JurisdictionClaim(UUID.randomUUID(), lord.id(), settlement,
                OfficeAuthority.ASSESS_TAX, 60, 0L, 0L, "领地权"));
        data.addClaim(new JurisdictionClaim(UUID.randomUUID(), council.id(), settlement,
                OfficeAuthority.ASSESS_TAX, 60, 0L, 0L, "居民宪章"));

        assertTrue(data.jurisdictionContested(settlement, OfficeAuthority.ASSESS_TAX, 1L));
        assertEquals(2, data.jurisdiction(settlement, OfficeAuthority.ASSESS_TAX, 1L).size());
    }

    private static InstitutionRecord establish(InstitutionData data, RightsStateData rights,
                                                SubjectRef founder, UUID settlement, String name) {
        return InstitutionService.establish(data, rights, UUID.randomUUID(), name,
                InstitutionKind.LORD_HOUSEHOLD, settlement, UUID.randomUUID(), founder,
                Set.of(OfficeAuthority.APPOINT_OFFICER), 0L, "test");
    }
}
