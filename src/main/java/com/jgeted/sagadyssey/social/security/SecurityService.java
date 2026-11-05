package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionRecord;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.social.justice.EvidenceKind;
import com.jgeted.sagadyssey.social.justice.EvidenceRef;
import com.jgeted.sagadyssey.social.justice.JusticeData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public final class SecurityService {
    private SecurityService() { }

    public static SecurityIncident report(SecurityData security, UUID incidentId,
                                          UUID settlementId, IncidentType type,
                                          String dimension, BlockPos position,
                                          SubjectRef victim, SubjectRef allegedOffender,
                                          int severity, SubjectRef reportedBy,
                                          long now, String note) {
        SecurityIncident incident = new SecurityIncident(incidentId, settlementId, type,
                dimension, position, victim, allegedOffender, severity, reportedBy, now,
                IncidentStatus.REPORTED, List.of(), List.of(), null, note, 0);
        if (!security.addIncident(incident)) throw new IllegalStateException("治安事件已经存在");
        return incident;
    }

    public static SecurityIncident addWitness(SecurityData security, UUID incidentId,
                                              WitnessObservation witness) {
        SecurityIncident incident = requireIncident(security, incidentId);
        SecurityIncident updated = incident.withWitness(witness);
        security.updateIncident(updated);
        return updated;
    }

    public static SecurityIncident addEvidence(SecurityData security, UUID incidentId,
                                               EvidenceRef evidence) {
        SecurityIncident incident = requireIncident(security, incidentId);
        SecurityIncident updated = incident.withEvidence(evidence);
        security.updateIncident(updated);
        return updated;
    }

    public static SecurityIncident verify(SecurityData security, InstitutionData institutions,
                                          TransferLedgerData transfers, InjuryData injuries,
                                          UUID incidentId, UUID institutionId,
                                          SubjectRef investigator, long now) {
        SecurityIncident incident = requireIncident(security, incidentId);
        var authority = institutions.authority(investigator, institutionId,
                OfficeAuthority.INVESTIGATE, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        InstitutionRecord institution = institutions.institution(institutionId);
        if (institution == null || institution.settlementId() == null
                || !institution.settlementId().equals(incident.settlementId())) {
            throw new IllegalStateException("调查机构不在事件管辖地");
        }
        int witnessWeight = incident.witnesses().stream()
                .collect(java.util.stream.Collectors.toMap(WitnessObservation::witness,
                        WitnessObservation::confidence, Math::max)).values().stream()
                .mapToInt(Integer::intValue).sum();
        boolean physicalEvidence = incident.evidence().stream().anyMatch(value ->
                value.kind() == EvidenceKind.TRANSFER_RECEIPT
                        && transfers.receipt(value.recordId()) != null
                        || value.kind() == EvidenceKind.INJURY_RECORD
                        && injuries.wound(value.recordId()) != null);
        if (!physicalEvidence && (incident.witnesses().stream()
                .map(WitnessObservation::witness).distinct().count() < 2 || witnessWeight < 120)) {
            throw new IllegalStateException("证据尚不足以核验事件");
        }
        SecurityIncident verified = incident.verified(investigator,
                physicalEvidence ? "已核对实体记录" : "两名独立证人相互印证");
        security.updateIncident(verified);
        return verified;
    }

    public static PatrolDispatch dispatch(SecurityData security, InstitutionData institutions,
                                          UUID incidentId, UUID institutionId,
                                          SubjectRef commander, List<SubjectRef> guards,
                                          long now, long expiresAt, String orders) {
        SecurityIncident incident = requireIncident(security, incidentId);
        if (incident.status() != IncidentStatus.VERIFIED
                && incident.status() != IncidentStatus.RESPONDING) {
            throw new IllegalStateException("未经核验的报告不能触发强制巡逻响应");
        }
        var authority = institutions.authority(commander, institutionId,
                OfficeAuthority.COMMAND_PATROL, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        for (SubjectRef guard : guards) {
            if (institutions.membership(institutionId, guard) == null) {
                throw new IllegalStateException("派遣名单中存在非本机构成员");
            }
        }
        PatrolDispatch dispatch = new PatrolDispatch(UUID.randomUUID(), incidentId,
                institutionId, commander, guards, now, expiresAt,
                PatrolDispatchStatus.EN_ROUTE, orders);
        security.addDispatch(dispatch);
        security.updateIncident(incident.responding());
        return dispatch;
    }

    public static int responsePriority(SecurityIncident incident, JusticeData justice, long now) {
        int result = incident.severity();
        if (incident.status() == IncidentStatus.VERIFIED) result += 15;
        if (incident.victim() != null
                && !justice.activeProtections(incident.victim(), now).isEmpty()) result += 20;
        if (incident.type() == IncidentType.BANDIT_ATTACK) result += 15;
        return Math.max(0, Math.min(100, result));
    }

    private static SecurityIncident requireIncident(SecurityData security, UUID id) {
        SecurityIncident incident = security.incident(id);
        if (incident == null) throw new IllegalStateException("治安事件不存在");
        return incident;
    }
}
