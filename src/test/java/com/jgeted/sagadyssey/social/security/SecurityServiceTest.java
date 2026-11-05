package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionKind;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.InstitutionService;
import com.jgeted.sagadyssey.social.institution.MembershipRank;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.social.justice.EvidenceKind;
import com.jgeted.sagadyssey.social.justice.EvidenceRef;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SecurityServiceTest {
    @Test
    void independentWitnessesEnableAuthorizedInvestigationAndRealMemberDispatch() {
        InstitutionData institutions = new InstitutionData();
        RightsStateData rights = new RightsStateData();
        SecurityData security = new SecurityData();
        TransferLedgerData transfers = new TransferLedgerData();
        InjuryData injuries = new InjuryData();
        UUID militiaId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        SubjectRef captain = SubjectRef.character(UUID.randomUUID());
        SubjectRef guard = SubjectRef.character(UUID.randomUUID());
        SubjectRef victim = SubjectRef.character(UUID.randomUUID());
        SubjectRef suspect = SubjectRef.character(UUID.randomUUID());
        InstitutionService.establish(institutions, rights, militiaId, "河湾守备队",
                InstitutionKind.MILITIA, settlementId, UUID.randomUUID(), captain,
                Set.of(OfficeAuthority.INVESTIGATE, OfficeAuthority.COMMAND_PATROL),
                0L, "居民证词须经核验");
        institutions.addMembership(new InstitutionMembership(UUID.randomUUID(), militiaId,
                guard, MembershipRank.MEMBER, 60, 0L, true, "值勤守卫"));
        UUID incidentId = UUID.randomUUID();
        SecurityService.report(security, incidentId, settlementId, IncidentType.THEFT,
                "minecraft:overworld", new BlockPos(10, 64, 10), victim, suspect,
                65, victim, 10L, "仓库物品失窃");
        SecurityService.addWitness(security, incidentId, new WitnessObservation(
                UUID.randomUUID(), SubjectRef.character(UUID.randomUUID()), suspect,
                70, 9L, 11L, "看见嫌疑人离开仓库"));

        assertThrows(IllegalStateException.class, () -> SecurityService.verify(security,
                institutions, transfers, injuries, incidentId, militiaId, captain, 12L));
        SecurityService.addWitness(security, incidentId, new WitnessObservation(
                UUID.randomUUID(), SubjectRef.character(UUID.randomUUID()), suspect,
                60, 9L, 13L, "听到破门声后看见其携物离开"));
        assertThrows(IllegalStateException.class, () -> SecurityService.verify(security,
                institutions, transfers, injuries, incidentId, militiaId,
                SubjectRef.character(UUID.randomUUID()), 14L));

        SecurityIncident verified = SecurityService.verify(security, institutions,
                transfers, injuries, incidentId, militiaId, captain, 15L);
        assertEquals(IncidentStatus.VERIFIED, verified.status());
        PatrolDispatch dispatch = SecurityService.dispatch(security, institutions,
                incidentId, militiaId, captain, List.of(guard),
                16L, 200L, "前往现场、保护仓库并寻找实物线索");
        assertEquals(PatrolDispatchStatus.EN_ROUTE, dispatch.status());
        assertEquals(IncidentStatus.RESPONDING, security.incident(incidentId).status());
        assertEquals(1, security.dispatchesFor(incidentId).size());

        SecurityService.addEvidence(security, incidentId, new EvidenceRef(UUID.randomUUID(),
                EvidenceKind.INJURY_RECORD, UUID.randomUUID(), captain, 17L,
                "响应途中补录的伤情证据"));
        assertEquals(IncidentStatus.RESPONDING, security.incident(incidentId).status(),
                "late evidence must not regress an active response to review");
    }

    @Test
    void unverifiedRumorCannotDispatchForce() {
        InstitutionData institutions = new InstitutionData();
        RightsStateData rights = new RightsStateData();
        SecurityData security = new SecurityData();
        UUID institutionId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        SubjectRef captain = SubjectRef.character(UUID.randomUUID());
        SubjectRef guard = SubjectRef.character(UUID.randomUUID());
        InstitutionService.establish(institutions, rights, institutionId, "夜巡队",
                InstitutionKind.MILITIA, settlementId, UUID.randomUUID(), captain,
                Set.of(OfficeAuthority.COMMAND_PATROL), 0L, "章程");
        institutions.addMembership(new InstitutionMembership(UUID.randomUUID(), institutionId,
                guard, MembershipRank.MEMBER, 50, 0L, true, "守卫"));
        UUID incidentId = UUID.randomUUID();
        SecurityService.report(security, incidentId, settlementId,
                IncidentType.PUBLIC_DISORDER, "minecraft:overworld", BlockPos.ZERO,
                null, null, 20, SubjectRef.character(UUID.randomUUID()), 1L, "未经证实的传闻");

        assertThrows(IllegalStateException.class, () -> SecurityService.dispatch(security,
                institutions, incidentId, institutionId, captain, List.of(guard),
                2L, 100L, "不得执行"));
    }
}
