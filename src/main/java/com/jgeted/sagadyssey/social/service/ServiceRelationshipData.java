package com.jgeted.sagadyssey.social.service;

import com.jgeted.sagadyssey.social.life.NpcContractStatus;
import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashSet;

/** Durable authority source. Legacy entity owner fields are only a compatibility projection. */
public final class ServiceRelationshipData extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_service_relationships_v2";
    private static final int MAX_AUDITS = 2048;
    public static final Factory<ServiceRelationshipData> FACTORY =
            new Factory<>(ServiceRelationshipData::new, ServiceRelationshipData::load);

    private final Map<UUID, ServiceRelationship> relationships = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> byAgent = new HashMap<>();
    private final Deque<ServiceAuthorityAudit> audits = new ArrayDeque<>();

    public static ServiceRelationshipData get(MinecraftServer server) {
        return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(server, FACTORY, DATA_NAME);
    }

    public Collection<ServiceRelationship> relationships() {
        return List.copyOf(relationships.values());
    }
    @Nullable public ServiceRelationship relationship(UUID id) { return relationships.get(id); }
    public static final String OFFICE_ALLOWANCE_PREFIX="sagadyssey:office_allowance/v1/";
    /** Accepted supplementary payment terms. Grants no work, command, stock access or fiscal authority. */
    public ServiceRelationship recordAcceptedOfficeAllowance(UUID id,UUID principal,UUID agent,UUID parent,long parentRevision,int daily,long startsAt,long endsAt,long now){
        var source=relationships.get(parent);
        if(daily<1||daily>16||startsAt>now||endsAt<=startsAt||endsAt-startsAt>30L*24000||source==null||source.revision()!=parentRevision||!source.activeAt(now)||startsAt<source.startedAt()||!source.principalId().equals(principal)||!source.agentId().equals(agent)||source.endsAt()!=null&&endsAt>source.endsAt())throw new IllegalArgumentException("current accepted parent payment agreement required");
        var description=OFFICE_ALLOWANCE_PREFIX+parent+"/"+id;
        var created=new ServiceRelationship(id,principal,agent,ServiceBasis.INSTITUTIONAL_OFFICE,Set.of(AuthorityScope.RECEIVE_SERVICE_ALLOWANCE),List.of(new ServiceObligation(ObligationParty.PRINCIPAL,"office_allowance_daily",description,daily,"minecraft:emerald",Math.min(endsAt,startsAt+24000),ObligationStatus.PENDING)),new ServiceLimits(source.limits().workplaceSettlementId(),source.limits().dimension(),false,false),ServiceStatus.ACTIVE,startsAt,endsAt,0,description);
        var old=relationships.get(id);if(old!=null){if(!old.equals(created))throw new IllegalStateException("allowance agreement identity occupied");return old;}
        if(relationships.size()>=16384)throw new IllegalStateException("service relationship capacity");insert(created);setDirty();return created;
    }
    /** Ending accrual preserves the principal's historical payment obligation and its exact stopping time. */
    public ServiceRelationship stopOfficeAllowance(UUID id,long at){
        var old=relationships.get(id);if(old==null||old.basis()!=ServiceBasis.INSTITUTIONAL_OFFICE||!old.scopes().equals(Set.of(AuthorityScope.RECEIVE_SERVICE_ALLOWANCE))||!old.statusReason().startsWith(OFFICE_ALLOWANCE_PREFIX)||at<old.startedAt()||old.endsAt()==null)throw new IllegalArgumentException("original allowance agreement required");
        if(!old.status().grantsAuthority())return old;
        if(at>old.endsAt())throw new IllegalArgumentException("allowance stop exceeds original term");
        var stopped=old.withStatus(ServiceStatus.CANCELLED,old.statusReason()+";accrual_stopped",at);relationships.put(id,stopped);setDirty();return stopped;
    }
    /** Indexed read, without audit writes or allocating a whole-world relationship snapshot. */
    public boolean permits(UUID principal,UUID agent,Set<AuthorityScope> scopes,boolean dangerous,String dimension,UUID workplace,long now) {
        for (UUID id : byAgent.getOrDefault(agent, Set.of())) {
            if (permits(relationships.get(id), principal, agent, scopes, dangerous, dimension, workplace, now)) return true;
        }
        return false;
    }
    public static boolean permits(ServiceRelationship r,UUID principal,UUID agent,Set<AuthorityScope> scopes,boolean dangerous,String dimension,UUID workplace,long now) {
        return r!=null&&r.principalId().equals(principal)&&r.agentId().equals(agent)&&r.activeAt(now)&&r.scopes().containsAll(scopes)
                &&(!dangerous||r.limits().dangerousOrdersAllowed())&&(r.limits().dimension().isBlank()||r.limits().dimension().equals(dimension))
                &&(r.limits().workplaceSettlementId()==null||r.limits().workplaceSettlementId().equals(workplace));
    }
    /** Trusted accepted-proposal writer. Adds one bounded delegation; ordinary employment and private title remain separate. Historical/revoked IDs never reactivate. */
    public ServiceRelationship acceptRiskDelegation(UUID id,UUID principal,UUID agent,UUID parent,long parentRevision,Set<AuthorityScope> scopes,
            ServiceLimits limits,long now,long endsAt,com.jgeted.sagadyssey.social.life.NpcAgencyDecision.ServiceOfferDecision decision) {
        var old=relationships.get(id);if(old!=null){if(!old.principalId().equals(principal)||!old.agentId().equals(agent)||old.basis()!=ServiceBasis.TEMPORARY_DELEGATION||!old.scopes().equals(scopes)||!old.limits().equals(limits)||!java.util.Objects.equals(old.endsAt(),endsAt))throw new IllegalStateException("delegation identity conflict");return old;}
        var source=relationships.get(parent);
        if(!decision.accepted()||source==null||source.revision()!=parentRevision||!source.activeAt(now)||!source.principalId().equals(principal)||!source.agentId().equals(agent)
                ||!source.scopes().containsAll(scopes)||!limits.dangerousOrdersAllowed()||limits.privateInventoryAccess()||limits.dimension().isBlank()||endsAt<=now
                ||!source.limits().dimension().isBlank()&&!source.limits().dimension().equals(limits.dimension())
                ||!java.util.Objects.equals(source.limits().workplaceSettlementId(),limits.workplaceSettlementId()))throw new IllegalArgumentException("accepted current parent and scoped risk agreement required");
        if(relationships.size()>=16384)throw new IllegalStateException("service relationship capacity");
        var created=new ServiceRelationship(id,principal,agent,ServiceBasis.TEMPORARY_DELEGATION,scopes,List.of(),limits,ServiceStatus.ACTIVE,now,endsAt,0,"明确接受的限定职责提议 "+id);
        insert(created);setDirty();return created;
    }
    public boolean endDelegation(UUID id,UUID principal,UUID agent,String reason,long now) {
        var r=relationships.get(id);if(r==null)return false;
        if(r.basis()!=ServiceBasis.TEMPORARY_DELEGATION||!r.principalId().equals(principal)||!r.agentId().equals(agent))throw new IllegalArgumentException("foreign delegation");
        if(!r.status().grantsAuthority())return true;relationships.put(id,r.withStatus(ServiceStatus.CANCELLED,reason,now));setDirty();return true;
    }
    private void insert(ServiceRelationship value) { relationships.put(value.id(),value);byAgent.computeIfAbsent(value.agentId(),ignored->new LinkedHashSet<>()).add(value.id()); }

    public List<ServiceAuthorityAudit> audits() { return List.copyOf(audits); }

    public List<ServiceRelationship> activeForAgent(UUID agentId, long gameTime) {
        return byAgent.getOrDefault(agentId,Set.of()).stream().map(relationships::get).filter(relationship -> relationship.agentId().equals(agentId)
                        && relationship.activeAt(gameTime))
                .sorted(Comparator.comparingLong(ServiceRelationship::startedAt).reversed()).toList();
    }

    public boolean hasHistory(UUID agentId) {
        return byAgent.containsKey(agentId);
    }

    public boolean hasActiveBetween(UUID principalId, UUID agentId, long gameTime) {
        for (UUID id : byAgent.getOrDefault(agentId, Set.of())) {
            var relationship = relationships.get(id);
            if (relationship.principalId().equals(principalId) && relationship.activeAt(gameTime)) return true;
        }
        return false;
    }

    public boolean hasScope(UUID principalId, UUID agentId, AuthorityScope scope, long gameTime) {
        for (UUID id : byAgent.getOrDefault(agentId, Set.of())) {
            var relationship = relationships.get(id);
            if (relationship.principalId().equals(principalId) && relationship.activeAt(gameTime)
                    && relationship.scopes().contains(scope)) return true;
        }
        return false;
    }

    /** One-time import of an old owner link. It grants old commands, never private inventory title. */
    public ServiceRelationship ensureLegacyRecruitment(UUID principalId, UUID agentId, long gameTime) {
        ServiceRelationship existing = byAgent.getOrDefault(agentId, Set.of()).stream().map(relationships::get)
                .filter(value -> value.principalId().equals(principalId) && value.status().grantsAuthority())
                .findFirst().orElse(null);
        if (existing != null) return existing;
        UUID id = named("legacy:" + principalId + ':' + agentId);
        ServiceRelationship historical = relationships.get(id);
        if (historical != null) return historical;
        ServiceRelationship created = new ServiceRelationship(id, principalId, agentId,
                ServiceBasis.LEGACY_RECRUITMENT, ordinaryScopes(),
                List.of(new ServiceObligation(ObligationParty.AGENT, "ordinary_service",
                                "在授权范围内提供普通服务", 0, "", Long.MAX_VALUE,
                                ObligationStatus.PENDING),
                        new ServiceObligation(ObligationParty.PRINCIPAL, "care",
                                "不得将雇佣解释为对人格或私人财产的所有权", 0, "",
                                Long.MAX_VALUE, ObligationStatus.PENDING)),
                ServiceLimits.ordinary(), ServiceStatus.ACTIVE, gameTime, null, 0L,
                "由旧版 owner 字段迁移；只保留旧命令范围");
        insert(created);
        setDirty();
        return created;
    }

    public ServiceRelationship beginVoluntaryEmployment(UUID principalId, UUID agentId,
                                                        long gameTime) {
        endActive(principalId, agentId, ServiceStatus.CANCELLED,
                "由新的自愿雇佣关系取代", gameTime);
        UUID id = named("employment:" + principalId + ':' + agentId + ':' + gameTime);
        ServiceRelationship created = new ServiceRelationship(id, principalId, agentId,
                ServiceBasis.VOLUNTARY_EMPLOYMENT, ordinaryScopes(),
                List.of(new ServiceObligation(ObligationParty.AGENT, "ordinary_service",
                                "在授权范围内提供普通服务", 0, "", Long.MAX_VALUE,
                                ObligationStatus.PENDING),
                        new ServiceObligation(ObligationParty.PRINCIPAL, "care",
                                "尊重拒绝、私人财产与既有社会身份", 0, "", Long.MAX_VALUE,
                                ObligationStatus.PENDING)),
                ServiceLimits.ordinary(), ServiceStatus.ACTIVE, gameTime, null, 0L,
                "双方以签约礼建立可解除的服务关系");
        insert(created);
        setDirty();
        return created;
    }

    public ServiceRelationship beginWageContract(NpcEmploymentContract contract, String dimension) {
        endActive(contract.employerCharacterId(), contract.npcId(), ServiceStatus.CANCELLED,
                "由有期限工资合同取代", contract.startedAt());
        UUID id = named("wage:" + contract.employerCharacterId() + ':' + contract.npcId()
                + ':' + contract.startedAt());
        ServiceRelationship created = new ServiceRelationship(id, contract.employerCharacterId(),
                contract.npcId(), ServiceBasis.WAGE_CONTRACT,
                EnumSet.of(AuthorityScope.FOLLOW, AuthorityScope.HOLD_POSITION,
                        AuthorityScope.WORK, AuthorityScope.LOGISTICS,
                        AuthorityScope.ASSIGN_WORKPLACE, AuthorityScope.ASSIGN_SCHEDULE,
                        AuthorityScope.MANAGE_ISSUED_EQUIPMENT, AuthorityScope.OPERATE_MOUNT,
                        AuthorityScope.END_SERVICE),
                List.of(new ServiceObligation(ObligationParty.PRINCIPAL, "wage",
                                "按期把实物工资送入代理人的私人储物", contract.wageEmeralds(),
                                "minecraft:emerald", contract.nextPaymentAt(), ObligationStatus.PENDING),
                        new ServiceObligation(ObligationParty.AGENT, "work",
                                "在约定聚落和期限内履行工作与物流任务", 0, "",
                                contract.endsAt(), ObligationStatus.PENDING)),
                new ServiceLimits(contract.settlementId(), dimension, false, false),
                ServiceStatus.ACTIVE, contract.startedAt(), contract.endsAt(), 0L,
                "有期限实物工资合同");
        insert(created);
        setDirty();
        return created;
    }

    public void syncContract(NpcEmploymentContract contract, long gameTime) {
        ServiceRelationship relationship = latestContract(contract.npcId(),
                contract.employerCharacterId());
        if (relationship == null) return;
        ServiceStatus next = switch (contract.status()) {
            case ACTIVE, PAYMENT_BLOCKED, IN_ARREARS -> ServiceStatus.ACTIVE;
            case COMPLETED -> ServiceStatus.COMPLETED;
            case CANCELLED -> ServiceStatus.CANCELLED;
            case BREACHED -> ServiceStatus.BREACHED;
        };
        ServiceRelationship updated = next == ServiceStatus.ACTIVE
                ? relationship.renewed(contract.endsAt(), contract.statusReason())
                : relationship.withStatus(next, contract.statusReason(), gameTime);
        relationships.put(updated.id(), updated);
        setDirty();
    }

    public boolean endActive(UUID principalId, UUID agentId, ServiceStatus status,
                             String reason, long gameTime) {
        boolean changed = false;
        for (UUID id : byAgent.getOrDefault(agentId, Set.of())) {
            var relationship = relationships.get(id);
            if (!relationship.principalId().equals(principalId)
                    || !relationship.activeAt(gameTime)) continue;
            relationships.put(relationship.id(), relationship.withStatus(status, reason, gameTime));
            changed = true;
        }
        if (changed) setDirty();
        return changed;
    }

    public void endActiveForAgent(UUID agentId, ServiceStatus status, String reason, long gameTime) {
        boolean changed = false;
        for (UUID id : byAgent.getOrDefault(agentId, Set.of())) {
            var relationship = relationships.get(id);
            if (!relationship.activeAt(gameTime)) continue;
            relationships.put(relationship.id(), relationship.withStatus(status, reason, gameTime));
            changed = true;
        }
        if (changed) setDirty();
    }

    public void endActiveForPrincipal(UUID principalId, ServiceStatus status,
                                      String reason, long gameTime) {
        boolean changed = false;
        for (ServiceRelationship relationship : List.copyOf(relationships.values())) {
            if (!relationship.principalId().equals(principalId)
                    || !relationship.activeAt(gameTime)) continue;
            relationships.put(relationship.id(), relationship.withStatus(status, reason, gameTime));
            changed = true;
        }
        if (changed) setDirty();
    }

    public ServiceAuthorityVerdict authorize(UUID principalId, UUID agentId, AuthorityScope scope,
                                             boolean dangerous, long gameTime) {
        var candidates = byAgent.getOrDefault(agentId, Set.of());
        List<ServiceRelationship> active = candidates.stream().map(relationships::get)
                .filter(value -> value.principalId().equals(principalId) && value.activeAt(gameTime)).toList();
        ServiceAuthorityVerdict verdict;
        if (active.isEmpty()) {
            String priorReason = candidates.stream().map(relationships::get)
                    .filter(value -> value.principalId().equals(principalId))
                    .max(Comparator.comparingLong(ServiceRelationship::startedAt))
                    .map(ServiceRelationship::statusReason).orElse("双方没有生效中的服务关系");
            verdict = new ServiceAuthorityVerdict(ServiceAuthorityVerdict.Status.DENY,
                    scope, List.of(), priorReason);
        } else {
            List<ServiceRelationship> scoped = active.stream()
                    .filter(value -> value.scopes().contains(scope)).toList();
            if (scoped.isEmpty()) {
                verdict = new ServiceAuthorityVerdict(ServiceAuthorityVerdict.Status.DENY, scope,
                        active.stream().map(ServiceRelationship::id).toList(),
                        "该命令不在已约定的服务范围内");
            } else if (dangerous && scoped.stream().noneMatch(value -> value.limits().dangerousOrdersAllowed())) {
                verdict = new ServiceAuthorityVerdict(ServiceAuthorityVerdict.Status.REFUSED, scope,
                        scoped.stream().map(ServiceRelationship::id).toList(),
                        "现有关系没有授权危险命令，NPC 可以拒绝");
            } else {
                verdict = new ServiceAuthorityVerdict(ServiceAuthorityVerdict.Status.ALLOW, scope,
                        scoped.stream().map(ServiceRelationship::id).toList(), "授权来自服务关系");
            }
        }
        audits.addLast(new ServiceAuthorityAudit(principalId, agentId, scope,
                verdict.status(), verdict.reason(), gameTime));
        while (audits.size() > MAX_AUDITS) audits.removeFirst();
        setDirty();
        return verdict;
    }

    private void putLoaded(ServiceRelationship relationship) {
        if (relationships.containsKey(relationship.id()))
            throw new IllegalArgumentException("Duplicate service relationship: " + relationship.id());
        insert(relationship);
    }
    private void putLoaded(ServiceAuthorityAudit audit) { audits.addLast(audit); }

    @Nullable
    private ServiceRelationship latestContract(UUID agentId, UUID principalId) {
        return byAgent.getOrDefault(agentId, Set.of()).stream().map(relationships::get)
                .filter(value -> value.principalId().equals(principalId)
                        && value.basis() == ServiceBasis.WAGE_CONTRACT)
                .max(Comparator.comparingLong(ServiceRelationship::startedAt)).orElse(null);
    }

    private static EnumSet<AuthorityScope> ordinaryScopes() {
        return EnumSet.of(AuthorityScope.FOLLOW, AuthorityScope.HOLD_POSITION,
                AuthorityScope.WORK, AuthorityScope.LOGISTICS, AuthorityScope.PATROL,
                AuthorityScope.DEFEND_PRINCIPAL, AuthorityScope.ASSIGN_WORKPLACE,
                AuthorityScope.ASSIGN_SCHEDULE, AuthorityScope.ASSIGN_ROLE,
                AuthorityScope.MANAGE_ISSUED_EQUIPMENT, AuthorityScope.OPERATE_MOUNT,
                AuthorityScope.END_SERVICE);
    }

    private static UUID named(String key) {
        return UUID.nameUUIDFromBytes(("sagadyssey:service:" + key).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag relationList = new ListTag();
        relationships.values().forEach(value -> relationList.add(value.save()));
        tag.put("Relationships", relationList);
        ListTag auditList = new ListTag();
        audits.forEach(value -> auditList.add(value.save()));
        tag.put("Audits", auditList);
        return tag;
    }

    static ServiceRelationshipData load(CompoundTag tag, HolderLookup.Provider registries) {
        ServiceRelationshipData data = new ServiceRelationshipData();
        ListTag relations = com.jgeted.sagadyssey.worldsim.PersistentDataAccess.compounds(tag, "Relationships");
        for (int index = 0; index < relations.size(); index++) {
            try {
                data.putLoaded(ServiceRelationship.load(relations.getCompoundOrEmpty(index)));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid service relationship at " + index, exception);
            }
        }
        ListTag audits = com.jgeted.sagadyssey.worldsim.PersistentDataAccess.compounds(tag, "Audits");
        int start = Math.max(0, audits.size() - MAX_AUDITS);
        for (int index = 0; index < audits.size(); index++) {
            try {
                var audit = ServiceAuthorityAudit.load(audits.getCompoundOrEmpty(index));
                if (index >= start) data.putLoaded(audit);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid service audit at " + index, exception);
            }
        }
        return data;
    }
}
