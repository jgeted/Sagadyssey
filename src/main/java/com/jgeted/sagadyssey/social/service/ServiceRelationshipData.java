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

/** Durable authority source. Legacy entity owner fields are only a compatibility projection. */
public final class ServiceRelationshipData extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_service_relationships_v2";
    private static final int MAX_AUDITS = 2048;
    public static final Factory<ServiceRelationshipData> FACTORY =
            new Factory<>(ServiceRelationshipData::new, ServiceRelationshipData::load);

    private final Map<UUID, ServiceRelationship> relationships = new LinkedHashMap<>();
    private final Deque<ServiceAuthorityAudit> audits = new ArrayDeque<>();

    public static ServiceRelationshipData get(MinecraftServer server) {
        return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(server, FACTORY, DATA_NAME);
    }

    public Collection<ServiceRelationship> relationships() {
        return List.copyOf(relationships.values());
    }

    public List<ServiceAuthorityAudit> audits() { return List.copyOf(audits); }

    public List<ServiceRelationship> activeForAgent(UUID agentId, long gameTime) {
        return relationships.values().stream().filter(relationship -> relationship.agentId().equals(agentId)
                        && relationship.activeAt(gameTime))
                .sorted(Comparator.comparingLong(ServiceRelationship::startedAt).reversed()).toList();
    }

    public boolean hasHistory(UUID agentId) {
        return relationships.values().stream().anyMatch(value -> value.agentId().equals(agentId));
    }

    public boolean hasActiveBetween(UUID principalId, UUID agentId, long gameTime) {
        return relationships.values().stream().anyMatch(value -> value.principalId().equals(principalId)
                && value.agentId().equals(agentId) && value.activeAt(gameTime));
    }

    public boolean hasScope(UUID principalId, UUID agentId, AuthorityScope scope, long gameTime) {
        return relationships.values().stream().anyMatch(value -> value.principalId().equals(principalId)
                && value.agentId().equals(agentId) && value.activeAt(gameTime)
                && value.scopes().contains(scope));
    }

    /** One-time import of an old owner link. It grants old commands, never private inventory title. */
    public ServiceRelationship ensureLegacyRecruitment(UUID principalId, UUID agentId, long gameTime) {
        ServiceRelationship existing = relationships.values().stream()
                .filter(value -> value.principalId().equals(principalId)
                        && value.agentId().equals(agentId) && value.status().grantsAuthority())
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
        relationships.put(id, created);
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
        relationships.put(id, created);
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
        relationships.put(id, created);
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
        for (ServiceRelationship relationship : List.copyOf(relationships.values())) {
            if (!relationship.principalId().equals(principalId)
                    || !relationship.agentId().equals(agentId)
                    || !relationship.activeAt(gameTime)) continue;
            relationships.put(relationship.id(), relationship.withStatus(status, reason, gameTime));
            changed = true;
        }
        if (changed) setDirty();
        return changed;
    }

    public void endActiveForAgent(UUID agentId, ServiceStatus status, String reason, long gameTime) {
        boolean changed = false;
        for (ServiceRelationship relationship : List.copyOf(relationships.values())) {
            if (!relationship.agentId().equals(agentId) || !relationship.activeAt(gameTime)) continue;
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
        List<ServiceRelationship> active = relationships.values().stream()
                .filter(value -> value.principalId().equals(principalId)
                        && value.agentId().equals(agentId) && value.activeAt(gameTime)).toList();
        ServiceAuthorityVerdict verdict;
        if (active.isEmpty()) {
            String priorReason = relationships.values().stream()
                    .filter(value -> value.principalId().equals(principalId)
                            && value.agentId().equals(agentId))
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
        if (relationships.putIfAbsent(relationship.id(), relationship) != null)
            throw new IllegalArgumentException("Duplicate service relationship: " + relationship.id());
    }
    private void putLoaded(ServiceAuthorityAudit audit) { audits.addLast(audit); }

    @Nullable
    private ServiceRelationship latestContract(UUID agentId, UUID principalId) {
        return relationships.values().stream()
                .filter(value -> value.agentId().equals(agentId)
                        && value.principalId().equals(principalId)
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
