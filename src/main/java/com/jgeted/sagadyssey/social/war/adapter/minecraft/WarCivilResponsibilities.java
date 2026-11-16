package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.command.NpcDutyData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.war.api.WarCandidateReport.Problem;
import com.jgeted.sagadyssey.worldsim.lod.NpcLodData;
import net.minecraft.nbt.*;

import java.util.*;

/** 原责任的只读审计，接收由共享维护流程显式提供的数据；不调用任何get/ensure/clear。 */
public record WarCivilResponsibilities(NpcBrainData brain, FieldworkData fieldwork, SupplyFlowData supply,
                                       ProductionOrderData production, NpcDutyData duties, NpcLodData lod,
                                       SettlementDevelopmentData development) {
    public WarCivilResponsibilities {
        Objects.requireNonNull(brain); Objects.requireNonNull(fieldwork); Objects.requireNonNull(supply);
        Objects.requireNonNull(production); Objects.requireNonNull(duties); Objects.requireNonNull(lod); Objects.requireNonNull(development);
    }
    public Map<UUID, List<Problem>> inspect(Map<UUID, UUID> bodies) {
        Map<UUID, LinkedHashSet<Problem>> found = new HashMap<>(); bodies.keySet().forEach(id -> found.put(id, new LinkedHashSet<>()));
        bodies.forEach((person, body) -> {
            brain.active(body).ifPresent(plan -> found.get(person).add(Problem.of("ACTIVE_CIVIL_PLAN", plan.id())));
            fieldwork.pendingDelivery(body).ifPresent(delivery -> found.get(person).add(Problem.of("PENDING_FIELDWORK_DELIVERY")));
            if (fieldwork.hasUnresolvedOutputConsumption(body)) found.get(person).add(Problem.of("UNRESOLVED_OUTPUT_CONSUMPTION"));
            if (fieldwork.pendingCargo(body).values().stream().anyMatch(amount -> amount > 0)) found.get(person).add(Problem.of("FIELDWORK_CARGO_REQUIRES_HANDOFF"));
            if (!fieldwork.outstandingScaffolds(body).isEmpty()) found.get(person).add(Problem.of("OUTSTANDING_CIVIL_SCAFFOLDS"));
            for (var order : production.assignedTo(body)) if (!order.terminal()) found.get(person).add(Problem.of("ACTIVE_PRODUCTION_ORDER", order.id()));
            var duty = duties.active(body); if (duty != null) found.get(person).add(Problem.of("ACTIVE_CIVIL_DUTY", duty.id()));
            var checkpoint = lod.find(body);
            if (checkpoint != null && (!checkpoint.person().equals(person) || checkpoint.unloaded() || com.jgeted.sagadyssey.worldsim.lod.NpcLodRuntime.review(checkpoint))) {
                found.get(person).add(Problem.of("LOD_RECONCILIATION_REQUIRED"));
            }
        });
        // 全批次仅扫描一次物流索引，不枚举实体；REVIEW仍可能有实物责任，不能视为自由人。
        for (var route : supply.routes()) if (bodies.containsKey(route.carrierCharacter())
                && (route.stage() != SupplyFlowData.Stage.DONE && route.stage() != SupplyFlowData.Stage.CANCELLED || route.pending() != null)) {
            found.get(route.carrierCharacter()).add(Problem.of("SUPPLY_ROUTE_REQUIRES_HANDOFF", route.id()));
        }
        // 旧成长数据只读审计引用，存在登记时交原系统核对，不猜测其可被强制释放。
        Map<UUID, UUID> identity = new HashMap<>(); bodies.forEach((person, body) -> { identity.put(person, person); identity.put(body, person); });
        int[] visited = {0};
        for (var site : development.sites()) referenced(site, identity, found, "DEVELOPMENT_ASSIGNMENT_REQUIRES_HANDOFF", visited, 0);
        for (var move : development.applications()) if (Set.of("WALKING", "WAIT_DESTINATION", "ARRIVING").contains(move.getStringOr("State", ""))) {
            referenced(move, identity, found, "MIGRATION_REQUIRES_HANDOFF", visited, 0);
        }
        Map<UUID, List<Problem>> result = new HashMap<>(); found.forEach((person, problems) -> result.put(person, List.copyOf(problems)));
        return Map.copyOf(result);
    }
    private static void referenced(Tag value, Map<UUID, UUID> identity, Map<UUID, LinkedHashSet<Problem>> found,
                                   String code, int[] visited, int depth) {
        if (++visited[0] > 16_384 || depth > 16) throw new IllegalStateException("civil source snapshot exceeds inspection budget");
        if (value instanceof CompoundTag row) {
            for (String key : row.keySet()) {
                if (++visited[0] > 16_384) throw new IllegalStateException("civil source snapshot exceeds inspection budget");
                if (row.read(key, UUIDUtil.CODEC).isPresent()) { UUID person = identity.get(row.read(key, UUIDUtil.CODEC).orElseThrow()); if (person != null) found.get(person).add(Problem.of(code)); }
                else if (row.get(key) instanceof CompoundTag || row.get(key) instanceof ListTag) referenced(row.get(key), identity, found, code, visited, depth + 1);
            }
        } else if (value instanceof ListTag list) for (Tag item : list) referenced(item, identity, found, code, visited, depth + 1);
    }
}
