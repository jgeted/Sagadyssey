package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** 真实装备借用；空槽不足、原工具冲突、损坏或缺失都保留责任供核对，不覆盖物品。 */
public final class WarKitAdapter implements EffectPort, AutoCloseable {
    public static final String FILE_NAME = "sagadyssey_war_kits.sgw";
    private final DurableSnapshotStore<UUID, WarKitRecord> store;
    private final PhysicalItemService physical;
    private final RightsStateData rights;
    private final Function<ItemLocation, Optional<WitnessedItemEndpoint>> resolve;
    private final Function<WarKitRecord, Optional<WarEquipmentSnapshot>> observe;
    public WarKitAdapter(Path file, PhysicalItemService physical, RightsStateData rights,
                         Function<ItemLocation, Optional<WitnessedItemEndpoint>> resolve, Function<WarKitRecord, Optional<WarEquipmentSnapshot>> observe) throws IOException {
        this.physical = Objects.requireNonNull(physical); this.rights = Objects.requireNonNull(rights); this.resolve = Objects.requireNonNull(resolve); this.observe = Objects.requireNonNull(observe);
        store = new DurableSnapshotStore<>(file, new WarKitCodec(), value -> value.request().id(), WarKitRecord::follows, "sagadyssey-war-kits");
    }
    public int protocolVersion() { return 1; }
    public Collection<WarKitRecord> records() { store.epoch(); return store.desiredValues(); }
    public Optional<WarKitRecord> find(UUID id) { store.epoch(); return store.desired(id); }
    public boolean durable(UUID id) { var value = store.desired(id); return value.isPresent() && store.durable(id).filter(value.get()::equals).isPresent(); }
    public boolean priorAttemptsProvedUnapplied(WarKitRecord value) {
        for (var attempts : List.of(value.issues(), value.returns())) for (int i = 0; i < attempts.size() - 1; i++) {
            var prior = physical.find(attempts.get(i).id());
            if (prior.isEmpty() || !prior.get().intent().equals(attempts.get(i)) || prior.get().phase() != PhysicalItemOperation.Phase.DENIED) return false;
        } return true;
    }
    public boolean provedUnapplied(WarKitRecord value, boolean returning) {
        for (var intent : returning ? value.returns() : value.issues()) {
            var record = physical.find(intent.id()); if (record.isEmpty() || !record.get().intent().equals(intent) || record.get().phase() != PhysicalItemOperation.Phase.DENIED) return false;
        } return true;
    }
    public boolean confirms(WarMobilization.Receipt receipt, WarKitRecord value) {
        if(value.lines().isEmpty())return durable(value.request().id()) && (receipt.stage()==WarMobilization.Stage.EQUIP || receipt.stage()==WarMobilization.Stage.RETURN_EQUIPMENT)
                && receipt.contractId().equals(value.request().id()) && receipt.characterId().equals(value.request().characterId())
                && receipt.operationId().equals(WarMobilization.operationId(value.request().id(),receipt.stage())) && receipt.referenceId().equals(receipt.operationId())
                && receipt.recordedAtTick()>=value.request().requestedAtTick();
        var intent = receipt.stage() == WarMobilization.Stage.EQUIP ? value.issue() : value.returned();
        if (intent.isEmpty() || !receipt.referenceId().equals(intent.get().id())) return false;
        return physical.find(intent.get().id()).filter(record -> record.intent().equals(intent.get()) && record.phase() == PhysicalItemOperation.Phase.APPLIED
                && record.proof().orElseThrow().occurredAt() == receipt.recordedAtTick()).isPresent();
    }
    public Status configure(WarKitRecord plan) {
        if (plan.issue().isPresent() || plan.returned().isPresent()) throw new IllegalArgumentException("configuration contains effects");
        var old = store.desired(plan.request().id()); if (old.isPresent()) return old.get().request().equals(plan.request()) && old.get().body().equals(plan.body()) && old.get().armory().equals(plan.armory()) && old.get().lines().equals(plan.lines())
                ? status(store.persist(old.get()).status()) : Status.UNKNOWN;
        return status(store.persist(plan).status());
    }
    public Reply lookup(Effect effect) {
        var value = store.desired(effect.request().id()); if (value.isEmpty()) return Reply.state(Status.UNAVAILABLE, "REAL_KIT_PLAN_REQUIRED");
        var plan = value.get(); if (!plan.request().equals(effect.request())) return Reply.state(Status.UNKNOWN, "KIT_REQUEST_CONFLICT");
        if(plan.lines().isEmpty()){
            returning(effect);var saved=store.persist(plan);if(saved.status()!=DurableSnapshotStore.Status.APPLIED)return Reply.state(status(saved.status()),saved.reason());
            // Proves the explicit absence of borrowed assets, not an invented item transfer.
            long at=effect.priorReceipts().values().stream().mapToLong(WarMobilization.Receipt::recordedAtTick).max().orElse(plan.request().requestedAtTick());
            return Reply.applied(new WarMobilization.Receipt(plan.request().id(),plan.request().characterId(),effect.stage(),effect.operationId(),
                    effect.operationId(),at,false));
        }
        var intent = returning(effect) ? plan.returned() : plan.issue(); if (intent.isEmpty()) return Reply.state(Status.NOT_APPLIED, "kit step not accepted");
        var saved = store.persist(plan); if (saved.status() != DurableSnapshotStore.Status.APPLIED) return Reply.state(status(saved.status()), saved.reason());
        var endpoints = endpoints(plan); var item = physical.submit(intent.get(), endpoints, effect.now());
        if (item.status() == PhysicalItemService.Status.APPLIED) {
            // 回放历史领用即使装备已返还也返回原凭据；激活阶段另行核实当前配装。
            return Reply.applied(new WarMobilization.Receipt(effect.request().id(), effect.request().characterId(), effect.stage(), effect.operationId(),
                    intent.get().id(), item.proof().orElseThrow().occurredAt(), false));
        }
        if (item.status() == PhysicalItemService.Status.DENIED && item.reason().equals("STALE_REVISION")) return prepareAttempt(effect, plan);
        if (item.status() == PhysicalItemService.Status.DENIED) return Reply.state(Status.NOT_APPLIED, "KIT_PROVED_DENIED:" + item.reason());
        return Reply.state(switch (item.status()) {
            case PENDING -> Status.PENDING; case UNAVAILABLE -> Status.UNAVAILABLE; default -> Status.UNKNOWN;
        }, "KIT_" + item.status() + ":" + item.reason());
    }
    public Reply apply(Effect effect) {
        var old = lookup(effect); if (old.status() != Status.NOT_APPLIED) return old;
        var plan = store.desired(effect.request().id()).orElseThrow(); var last = returning(effect) ? plan.returned() : plan.issue();
        if (last.isPresent()) {
            var revisions = last.get().transfer().orElseThrow().expectedRevisions();
            if (revisions.stream().allMatch(value -> rights.context(value.containerId()) != null && rights.context(value.containerId()).revision() == value.revision()))
                return Reply.state(Status.UNAVAILABLE, "PROVED_DENIED_KIT_REQUIRES_RESOURCE_CHANGE_OR_DEMOBILIZATION");
        }
        return prepareAttempt(effect, plan);
    }
    /** 仅首个提交或已持久证明没有效果的库存版本冲突可进入这里。 */
    private Reply prepareAttempt(Effect effect, WarKitRecord plan) {
        boolean returning = returning(effect);
        int sequence = returning ? plan.returns().size() : plan.issues().size();
        if (sequence == 32) return Reply.state(Status.UNKNOWN, "KIT_ATTEMPT_HISTORY_FULL");
        var endpoints = endpoints(plan); var equipment = ItemLocation.equipment(plan.request().characterId(), plan.body());
        if (endpoints.size() != 2) return Reply.state(Status.UNAVAILABLE, "ORIGINAL_KIT_CONTAINERS_UNAVAILABLE");
        if (returning) {
            if (plan.issue().isEmpty() || physical.find(plan.issue().get().id()).filter(record -> record.phase() == PhysicalItemOperation.Phase.APPLIED).isEmpty()) return Reply.state(Status.UNKNOWN, "KIT_ISSUE_PROOF_REQUIRED");
            var container = endpoints.get(equipment.id()).container();
            for (var line : plan.lines()) for (int slot = 0; slot < container.getContainerSize(); slot++) if (line.item().matches(container.getItem(slot))) {
                var claim = rights.claimAt(equipment.id(), slot, line.item());
                if (claim == null || !claim.beneficialOwner().equals(SubjectRef.character(plan.request().actorId())) || !claim.encumbrances().contains("issued")) return Reply.state(Status.UNKNOWN, "RETURN_TITLE_OR_PROVENANCE_REQUIRES_REVIEW");
            }
        } else {
            var container = endpoints.get(equipment.id()).container();
            for (var line : plan.lines()) for (int slot = 0; slot < container.getContainerSize(); slot++) if (line.item().matches(container.getItem(slot))) return Reply.state(Status.UNAVAILABLE, "PERSONAL_GEAR_REQUIRES_EXPLICIT_HANDOFF");
        }
        var revisions = new ArrayList<ExpectedRevision>(); for (var id : endpoints.keySet().stream().sorted().toList()) {
            var context = rights.context(id); if (context == null) return Reply.state(Status.UNAVAILABLE, "NATIVE_KIT_RIGHTS_CONTEXT_REQUIRED"); revisions.add(new ExpectedRevision(id, context.revision()));
        }
        var legs = plan.lines().stream().map(line -> new TransferLeg(returning ? equipment.id() : plan.armory().id(), returning ? plan.armory().id() : equipment.id(), line.item(), line.quantity())).toList();
        var transfer = new TransferRequest(WarKitRecord.attemptId(plan.request().id(), returning, sequence), SubjectRef.character(plan.request().actorId()), returning ? TransferIntent.RETURN : TransferIntent.ISSUE, legs,
                revisions, "", effect.now(), returning ? Map.of(equipment.id(), SubjectRef.character(plan.request().characterId())) : Map.of());
        var intent = PhysicalItemOperation.Intent.transfer(transfer);
        var issues = new ArrayList<>(plan.issues()); var returns = new ArrayList<>(plan.returns()); if (returning) returns.add(intent); else issues.add(intent);
        var next = new WarKitRecord(plan.request(), plan.body(), plan.armory(), plan.lines(), issues, returns);
        var saved = store.persist(next); return Reply.state(status(saved.status()) == Status.APPLIED ? Status.PENDING : status(saved.status()), saved.reason());
    }
    public Reply cancel(Effect effect) { return lookup(effect); }
    public boolean equipped(UUID id) {
        var value = find(id); if (value.isEmpty()) return false; var expected = InitialWarDefinitions.LOADOUTS.find(value.get().request().loadout());
        return expected.isPresent() && observe.apply(value.get()).filter(snapshot -> snapshot.mismatches(expected.get()).isEmpty()).isPresent();
    }
    private Map<UUID, WitnessedItemEndpoint> endpoints(WarKitRecord plan) {
        var result = new HashMap<UUID, WitnessedItemEndpoint>(); resolve.apply(plan.armory()).ifPresent(e -> result.put(e.id(), e));
        resolve.apply(ItemLocation.equipment(plan.request().characterId(), plan.body())).ifPresent(e -> result.put(e.id(), e)); return Map.copyOf(result);
    }
    private static boolean returning(Effect effect) { if (effect.stage() != WarMobilization.Stage.EQUIP && effect.stage() != WarMobilization.Stage.RETURN_EQUIPMENT) throw new IllegalArgumentException("not kit stage"); return effect.stage() == WarMobilization.Stage.RETURN_EQUIPMENT; }
    private static Status status(DurableSnapshotStore.Status value) { return switch (value) { case APPLIED -> Status.APPLIED; case PENDING -> Status.PENDING; case UNKNOWN -> Status.UNKNOWN; case UNAVAILABLE -> Status.UNAVAILABLE; }; }
    @Override public void close() { store.close(); }
}
