package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarLoadoutCatalog;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;

import java.util.*;

/** 原生人物预检：只按已登记身体读取，不修绑定、不推进伤势、不创建领域数据。 */
public final class WarCandidateInspector {
    public static final Set<AuthorityScope> REQUIRED_SCOPES=Set.of(AuthorityScope.ASSIGN_ROLE,AuthorityScope.DEFEND_PRINCIPAL,AuthorityScope.MANAGE_ISSUED_EQUIPMENT);
    public record Sources(CharacterRegistryData characters, NpcLifeData life, InjuryData injuries,
                          ServiceRelationshipData services, WarCivilResponsibilities responsibilities) {
        public Sources {
            Objects.requireNonNull(characters); Objects.requireNonNull(life); Objects.requireNonNull(injuries);
            Objects.requireNonNull(services); Objects.requireNonNull(responsibilities);
        }
    }
    private final MinecraftServer server;
    private final Sources sources;
    private final WarLoadoutCatalog loadouts;
    public WarCandidateInspector(MinecraftServer server, Sources sources, WarLoadoutCatalog loadouts) {
        this.server = Objects.requireNonNull(server); this.sources = Objects.requireNonNull(sources); this.loadouts = Objects.requireNonNull(loadouts);
    }
    boolean belongsTo(MinecraftServer owner) { return server == owner; }
    /** 提交前的正式维护核验；该入口可调用共享维护服务，不用于只读预览。 */
    boolean originalResponsibilitiesPermitClaim(UUID character,UUID operation) {
        var body = registeredBody(character); if (body.isEmpty()) return false;
        return !com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime.blocksOther(server,character,operation)
                && !com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.controls(server, character,operation)
                && !com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(server, character)
                && com.jgeted.sagadyssey.social.household.ResidentWorkMigrationData.obligations(server, character, body.get(),operation) == null;
    }
    Optional<UUID> registeredBody(UUID character) {
        if (!server.isSameThread()) throw new IllegalStateException("candidate inspection requires server thread");
        return Optional.ofNullable(sources.characters().bodyOf(character)).map(binding -> binding.entityId());
    }
    boolean bodyAvailable(UUID character, UUID bodyId) {
        if (!server.isSameThread()) throw new IllegalStateException("candidate inspection requires server thread");
        var binding = sources.characters().bodyOf(character); if (binding == null || !binding.entityId().equals(bodyId)) return false;
        var dimension = Identifier.tryParse(binding.dimensionId()); if (dimension == null) return false;
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        return level != null && level.getEntity(bodyId) instanceof NpcBase npc && npc.isAlive() && npc.getCharacterId().equals(character);
    }
    public List<WarCandidateReport> inspect(List<WarMilitiaRequest> requested, long now) {
        if (!server.isSameThread()) throw new IllegalStateException("candidate inspection requires server thread");
        var requests = List.copyOf(requested);
        if (requests.isEmpty() || requests.size() > 128 || now != server.overworld().getGameTime()) throw new IllegalArgumentException("invalid live inspection batch/time");
        Set<UUID> ids = new HashSet<>(), people = new HashSet<>();
        for (var request : requests) if (!ids.add(request.id()) || !people.add(request.characterId()) || request.requestedAtTick() > now) {
            throw new IllegalArgumentException("duplicate or future candidate request");
        }
        // 一批取得一份服务索引；不调用会记录审计和setDirty的authorize。
        Map<UUID, NpcBase> loaded = new HashMap<>();
        Map<UUID, WarCandidateReport> early = new HashMap<>();
        for (var request : requests) {
            var principal = sources.characters().byId(request.actorId());
            if (principal == null || !principal.alive()) { early.put(request.id(), WarCandidateReport.unavailable(request, now, "ACTOR_CHARACTER_UNAVAILABLE")); continue; }
            var binding = sources.characters().bodyOf(request.characterId()); var life = sources.life().record(request.characterId());
            var consent = binding!=null && life!=null && sources.services().permits(request.actorId(),request.characterId(),REQUIRED_SCOPES,true,binding.dimensionId(),life.activeWorkplaceSettlementId(),now);
            if (!consent) { early.put(request.id(), WarCandidateReport.unavailable(request, now, "MILITARY_SERVICE_CONSENT_REQUIRED")); continue; }
            var character = sources.characters().byId(request.characterId());
            if (character == null || !character.alive()) { early.put(request.id(), WarCandidateReport.unavailable(request, now, "CHARACTER_UNKNOWN_OR_DEAD")); continue; }
            var dimension = binding == null ? null : Identifier.tryParse(binding.dimensionId());
            var level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            var body = level == null || binding == null ? null : level.getEntity(binding.entityId());
            if (!(body instanceof NpcBase npc) || !npc.isAlive() || !npc.getCharacterId().equals(request.characterId())) {
                early.put(request.id(), WarCandidateReport.unavailable(request, now, "REGISTERED_BODY_UNAVAILABLE")); continue;
            }
            loaded.put(request.characterId(), npc);
        }
        Map<UUID, UUID> bodies = new HashMap<>(); loaded.forEach((person, npc) -> bodies.put(person, npc.getUUID()));
        var responsibilities = bodies.isEmpty() ? Map.<UUID, List<WarCandidateReport.Problem>>of() : sources.responsibilities().inspect(bodies);
        List<WarCandidateReport> reports = new ArrayList<>();
        for (var request : requests) {
            if (early.containsKey(request.id())) { reports.add(early.get(request.id())); continue; }
            var npc = loaded.get(request.characterId()); var life = sources.life().record(request.characterId()); var needs = sources.life().needs(request.characterId());
            if (life == null || needs == null) { reports.add(WarCandidateReport.unavailable(request, now, "EXISTING_LIFE_OR_NEEDS_RECORD_REQUIRED")); continue; }
            var problems = new ArrayList<>(responsibilities.get(request.characterId()));
            var civilContract = sources.life().contract(request.characterId());
            if (civilContract != null && civilContract.status().ongoing()) problems.add(WarCandidateReport.Problem.of("CIVIL_WAGE_CONTRACT_REQUIRES_HANDOFF"));
            if (request.kind() == WarMilitiaRequest.Kind.MILITIA && Set.of(NpcProfession.WARRIOR, NpcProfession.ARCHER, NpcProfession.HEAVY).contains(npc.getProfession())) {
                problems.add(WarCandidateReport.Problem.of("CIVILIAN_MILITIA_SOURCE_REQUIRED"));
            }
            if (npc.isBaby()) problems.add(WarCandidateReport.Problem.of("ADULT_REQUIRED"));
            if (npc.isNoAi()) problems.add(WarCandidateReport.Problem.of("BODY_EXECUTION_DISABLED"));
            if (!npc.getTransportInventory().isEmpty()) problems.add(WarCandidateReport.Problem.of("CARRIED_CARGO_REQUIRES_HANDOFF"));
            if (npc.getTarget() != null || npc.isSleeping()) problems.add(WarCandidateReport.Problem.of("IMMEDIATE_ACTIVITY_REQUIRES_HANDOFF"));
            var health = sources.injuries().observeTriage(request.characterId());
            if (health.incapacitated() || health.mortalDanger()) problems.add(WarCandidateReport.Problem.of("HEALTH_PREVENTS_SERVICE"));
            if (needs.lastUpdatedAt() > now) problems.add(WarCandidateReport.Problem.of("NEEDS_TIME_REQUIRES_REVIEW"));
            if (life.authority() != NpcWorkAuthority.NONE
                    && (life.authority() != NpcWorkAuthority.SERVICE || !request.actorId().equals(life.authorityId()))) {
                problems.add(WarCandidateReport.Problem.of("CIVIL_AUTHORITY_RELEASE_REQUIRED"));
            }
            var target = loadouts.find(request.loadout());
            if (target.isEmpty()) { reports.add(WarCandidateReport.unavailable(request, now, "UNSUPPORTED_LOADOUT")); continue; }
            var equipment = WarEquipmentInspector.inspect(npc, target.get().primaryWeapon());
            var person = new WarCandidateReport.Person(npc.getUUID(), npc.level().dimension().identifier().toString(), npc.getProfession().name(),
                    life.authority().name(), npc.getHealth(), npc.getMaxHealth(), needs.hunger(), needs.lastUpdatedAt());
            reports.add(new WarCandidateReport(request.id(), request.characterId(), now, Optional.of(person), Optional.of(equipment), equipment.mismatches(target.get()), problems));
        }
        return List.copyOf(reports);
    }
}
