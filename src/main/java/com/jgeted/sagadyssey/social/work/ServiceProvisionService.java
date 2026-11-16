package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import java.io.IOException;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.*;
import java.util.function.*;

/** 有界的共享粮饷推进器；实际扣物委托共享实物服务，营养只写原生活档。 */
public final class ServiceProvisionService implements AutoCloseable {
    public enum Status { APPLIED, NOT_APPLIED, PENDING, UNKNOWN, UNAVAILABLE, CONFLICT }
    public record Reply(Status status, Optional<ServiceProvision.Mark> mark, String reason) {}
    private final DurableSnapshotStore<UUID, ServiceProvision> store;
    private final PhysicalItemService physical;
    private final RightsStateData rights;
    private final NpcLifeData life;
    private final Function<ItemLocation, Optional<WitnessedItemEndpoint>> resolve;
    private final ToIntFunction<ServiceProvision.Terms> activity;
    private final Map<UUID, String> problems = new HashMap<>();
    private final Set<UUID> verifiedHistory = new HashSet<>();
    private final LinkedHashSet<UUID> ready = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> settling = new LinkedHashSet<>();
    private final Map<UUID,Set<UUID>> pendingOwners = new HashMap<>();
    private long pendingEpoch=-1;private boolean pendingChanged=true;
    private long lastTick = -1;
    private final Map<UUID, Set<UUID>> needsByPerson = new HashMap<>();
    public ServiceProvisionService(Path path, PhysicalItemService physical, RightsStateData rights, NpcLifeData life,
                                   Function<ItemLocation, Optional<WitnessedItemEndpoint>> resolve, ToIntFunction<ServiceProvision.Terms> activity) throws IOException {
        this.physical = Objects.requireNonNull(physical); this.rights = Objects.requireNonNull(rights); this.life = Objects.requireNonNull(life);
        this.resolve = Objects.requireNonNull(resolve); this.activity = Objects.requireNonNull(activity);
        store = new DurableSnapshotStore<>(path, new ServiceProvisionCodec(), value -> value.terms().id(), ServiceProvision::follows, "sagadyssey-service-provision");
        for (var value : store.recover()) {
            ready.add(value.terms().id());
            if (value.pending().isPresent()) settling.add(value.terms().id());
            rememberNeeds(value);
        }
    }
    public Collection<ServiceProvision> records() { store.epoch(); return store.desiredValues(); }
    public Optional<ServiceProvision> find(UUID id) { store.epoch(); return store.desired(id); }
    public boolean durable(UUID id) { var value = store.desired(id); return value.isPresent() && store.durable(id).filter(value.get()::equals).isPresent(); }
    public Optional<String> problem(UUID id) { store.epoch(); return Optional.ofNullable(problems.get(id)); }
    public boolean ownsNeeds(UUID person) {
        store.epoch();
        var services = needsByPerson.get(person);
        if (services == null) return false;
        // Keep needs fenced until both the desired and published account release them.
        services.removeIf(id -> store.desired(id).filter(ServiceProvision::ownsNeeds).isEmpty()
                && store.durable(id).filter(ServiceProvision::ownsNeeds).isEmpty());
        if (!services.isEmpty()) return true;
        needsByPerson.remove(person);
        return false;
    }
    private void rememberNeeds(ServiceProvision value) {
        if (value.ownsNeeds()) needsByPerson.computeIfAbsent(value.terms().person(), ignored -> new HashSet<>()).add(value.terms().id());
    }
    /** 明确的可信配置，所有人物/物权仍须由调用方与原领域正式授权。 */
    public Reply configure(ServiceProvision.Terms terms, long now) {
        var prior = store.desired(terms.id());
        if (prior.isPresent()) return prior.get().terms().equals(terms) ? persisted(prior.get()) : reply(Status.CONFLICT, null, "SERVICE_TERMS_ID_REUSED");
        if (store.desiredValues().stream().anyMatch(v -> !v.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)
                && (v.terms().person().equals(terms.person()) || v.terms().body().equals(terms.body())))) return reply(Status.CONFLICT, null, "SERVICE_PERSON_ALREADY_CONFIGURED");
        var value = ServiceProvision.configured(terms, now); var result = persisted(value);
        if (store.desired(terms.id()).isPresent()) { ready.add(terms.id()); verifiedHistory.add(terms.id()); }
        return result;
    }
    public Reply lookup(UUID id, ServiceProvision.Step step, UUID operation) {
        var value = store.desired(id); if (value.isEmpty()) return reply(Status.UNAVAILABLE, null, "SERVICE_TERMS_REQUIRED");
        var mark = value.get().marks().get(step); if (mark == null) return reply(Status.NOT_APPLIED, null, "SERVICE_STEP_NOT_ACCEPTED");
        if (!mark.operation().equals(operation)) return reply(Status.CONFLICT, null, "SERVICE_STEP_ID_REUSED");
        store.persist(value.get()); var durable = store.durable(id).map(v -> v.marks().get(step)).orElse(null);
        if (mark.equals(durable)) return reply(Status.APPLIED, durable, "");
        var saved = persisted(value.get()); return saved.status() == Status.APPLIED ? reply(Status.PENDING, null, "SERVICE_MARK_ACK_PENDING") : saved;
    }
    public Reply mark(UUID id, ServiceProvision.Step step, UUID operation, long at, long now) {
        var prior = lookup(id, step, operation); if (prior.status() != Status.NOT_APPLIED) return prior;
        var value = store.desired(id).orElseThrow(); if (now < value.changedAt() || at > now) return reply(Status.CONFLICT, null, "SERVICE_TIME_REVERSED");
        if (!store.durable(id).filter(value::equals).isPresent()) { store.persist(value); return reply(Status.PENDING, null, "SERVICE_PREVIOUS_STATE_PENDING"); }
        try { var result = persisted(value.mark(step, operation, at, now)); ready.add(id); return result.status() == Status.APPLIED ? lookup(id, step, operation) : result; }
        catch (IllegalArgumentException exception) { return reply(Status.CONFLICT, null, "SERVICE_STEP_SEQUENCE_INVALID"); }
    }
    /** One pending settlement plus one fair rotating account per tick, never twice for the same account. */
    public void tick(long now) {
        store.epoch(); if (now <= lastTick || ready.isEmpty()&&settling.isEmpty()) return; lastTick = now;UUID processed=null;
        if(!settling.isEmpty()){var it=settling.iterator();processed=it.next();it.remove();advanceSafely(processed,now);if(store.desired(processed).orElseThrow().pending().isPresent())settling.add(processed);}
        if(!ready.isEmpty()){var iterator=ready.iterator();var id=iterator.next();iterator.remove();if(!id.equals(processed))advanceSafely(id,now);
            var value=store.desired(id).orElseThrow();if(!value.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)||value.pending().isPresent()||value.arrears(now).signum()>0||!store.durable(id).filter(value::equals).isPresent())ready.add(id);
        }
    }
    private void advanceSafely(UUID id,long now){try{advance(id,now);}catch(RuntimeException exception){problems.put(id,"SERVICE_STATE_REQUIRES_REVIEW");}}
    /** Admission only, never money or transfer authority. Durable pending intents keep leases until settlement publication. */
    private boolean availableFor(UUID service,Set<UUID> endpoints){
        long current=store.epoch();if(pendingChanged||current!=pendingEpoch){
            pendingOwners.clear();for(var rows:List.of(store.desiredValues(),store.durableValues()))for(var v:rows)v.pending().ifPresent(a->{for(var endpoint:a.intent().endpoints())pendingOwners.computeIfAbsent(endpoint,k->new HashSet<>()).add(v.terms().id());});
            pendingEpoch=current;pendingChanged=false;
        }
        return endpoints.stream().allMatch(id->pendingOwners.getOrDefault(id,Set.of()).stream().allMatch(owner->service.equals(owner)||yieldUnloadedIntent(owner)));
    }
    /** An unissued intent for an unloaded recipient cannot starve other wages. Its immutable revision will be rechecked on return. */
    private boolean yieldUnloadedIntent(UUID owner){
        var value=store.desired(owner).orElseThrow();if(value.pending().isEmpty()||resolve.apply(value.terms().bag()).isPresent())return false;
        var intent=value.pending().orElseThrow().intent();var record=physical.find(intent.id());
        return record.isEmpty()||record.get().phase()==PhysicalItemOperation.Phase.INTENT;
    }
    private void advance(UUID id, long now) {
        var value = store.desired(id).orElseThrow(); if (now < value.changedAt()) { problems.put(id, "SERVICE_TIME_REVERSED"); return; }
        if (persisted(value).status() != Status.APPLIED) return;
        if (!verifiedHistory.contains(id)) { if (!verifyHistory(value, now)) return; verifiedHistory.add(id); }
        if (value.ownsNeeds() && !life.accrueHungerThrough(value.terms().person(), now, Math.max(0, Math.min(3, activity.applyAsInt(value.terms()))))) { problems.put(id, "ORIGINAL_NEEDS_UNAVAILABLE_OR_REVERSED"); return; }
        if (value.pending().isPresent()) { finish(value, now); return; }
        if (value.settlements().size() == 1024) { problems.put(id, "SERVICE_HISTORY_FULL_REQUIRES_ARCHIVE"); return; }
        var bag = resolve.apply(value.terms().bag());
        if (bag.isEmpty()) { problems.put(id, "ORIGINAL_BODY_UNAVAILABLE"); return; }
        var needs = life.needs(value.terms().person());
        if (value.ownsNeeds() && needs != null && needs.hunger() >= 350) {
            if(!availableFor(id,Set.of(bag.get().id()))){problems.put(id,"ORIGINAL_RATION_CONTAINER_BUSY");return;}
            if (!life.canRecordMeal(ServiceProvision.operation(id, ServiceProvision.Purpose.MEAL, value.nextSequence()))) { problems.put(id, "MEAL_HISTORY_FULL"); return; }
            var container = bag.get().container(); int slot = NpcFoodItems.findPlannedFoodSlot(container, needs.hunger() >= 800);
            if (slot >= 0) {
                var item = container.getItem(slot); var context = rights.context(bag.get().id());
                if (context == null || physical.hasUnresolved(bag.get().id()) && !physical.verifyOwner(bag.get())) { problems.put(id, "RATION_CONTAINER_REQUIRES_REVIEW"); return; }
                var op = ServiceProvision.operation(id, ServiceProvision.Purpose.MEAL, value.nextSequence());
                var consume = new PhysicalItemOperation.Consumption(op, bag.get().id(), SubjectRef.character(value.terms().person()), ItemFingerprint.of(item), 1,
                        "", "service-ration:" + id, context.revision(), now);
                persisted(value.attempt(new ServiceProvision.Attempt(ServiceProvision.Purpose.MEAL, value.nextSequence(), PhysicalItemOperation.Intent.consume(consume), NpcFoodItems.hungerRestore(item)), now)); return;
            }
            problems.put(id, "RATIONS_EXHAUSTED");
        }
        var owed = value.arrears(now); if (owed.signum() == 0) return;
        var treasury = resolve.apply(value.terms().treasury()); if (treasury.isEmpty()) { problems.put(id, "TREASURY_UNAVAILABLE_ARREARS_RETAINED"); return; }
        if(!availableFor(id,Set.of(treasury.get().id(),bag.get().id()))){problems.put(id,"ORIGINAL_PAYROLL_CONTAINER_BUSY");return;}
        for(var endpoint:List.of(treasury.get(),bag.get()))if(physical.hasUnresolved(endpoint.id())){
            if(!physical.verifyOwner(endpoint)){problems.put(id,"PAYROLL_CONTAINER_REQUIRES_REVIEW");return;}
            if(physical.hasUnresolved(endpoint.id())){problems.put(id,"ORIGINAL_PAYROLL_CONTAINER_BUSY");return;}
        }
        int available = 0; for (int i = 0; i < treasury.get().container().getContainerSize(); i++) {
            var stack = treasury.get().container().getItem(i); if (value.terms().wageItem().matches(stack)) available = Math.min(64, available + stack.getCount());
        }
        if (available == 0) { problems.put(id, "TREASURY_EMPTY_ARREARS_RETAINED"); return; }
        int amount = owed.min(BigInteger.valueOf(available)).intValueExact(); var endpoints = Map.of(treasury.get().id(), treasury.get(), bag.get().id(), bag.get());
        var revisions = revisions(endpoints.keySet()); if (revisions.isEmpty()) { problems.put(id, "PAYROLL_RIGHTS_CONTEXT_REQUIRED"); return; }
        var request = new TransferRequest(ServiceProvision.operation(id, ServiceProvision.Purpose.WAGE, value.nextSequence()), value.terms().payer(), TransferIntent.PAYMENT,
                List.of(new TransferLeg(treasury.get().id(), bag.get().id(), value.terms().wageItem(), amount)), revisions, "", now);
        persisted(value.attempt(new ServiceProvision.Attempt(ServiceProvision.Purpose.WAGE, value.nextSequence(), PhysicalItemOperation.Intent.transfer(request), amount), now));
    }
    private List<ExpectedRevision> revisions(Set<UUID> ids) {
        var result = new ArrayList<ExpectedRevision>(); for (var id : ids.stream().sorted().toList()) { var context = rights.context(id); if (context == null) return List.of(); result.add(new ExpectedRevision(id, context.revision())); } return List.copyOf(result);
    }
    private Map<UUID, WitnessedItemEndpoint> endpoints(ServiceProvision value, ServiceProvision.Attempt attempt) {
        var result = new HashMap<UUID, WitnessedItemEndpoint>();
        resolve.apply(value.terms().bag()).ifPresent(e -> result.put(e.id(), e));
        if (attempt.purpose() == ServiceProvision.Purpose.WAGE) resolve.apply(value.terms().treasury()).ifPresent(e -> result.put(e.id(), e)); return Map.copyOf(result);
    }
    private void finish(ServiceProvision value, long now) {
        var attempt = value.pending().orElseThrow(); var reply = physical.submit(attempt.intent(), endpoints(value, attempt), now);
        if (reply.status() == PhysicalItemService.Status.APPLIED) {
            var proof = reply.proof().orElseThrow();
            if (attempt.purpose() == ServiceProvision.Purpose.MEAL) life.feedOnce(attempt.intent().id(), value.terms().person(), attempt.value(), "service-ration:" + value.terms().id(), proof.occurredAt(), now);
            persisted(value.settled(true, proof.occurredAt(), now)); problems.remove(value.terms().id());
        } else if (reply.status() == PhysicalItemService.Status.DENIED) {
            persisted(value.settled(false, reply.proof().orElseThrow().occurredAt(), now)); problems.put(value.terms().id(), "SHARED_ITEM_DENIED:" + reply.reason());
        } else if (reply.status() != PhysicalItemService.Status.PENDING) problems.put(value.terms().id(), "SHARED_ITEM_" + reply.status() + ":" + reply.reason());
    }
    private boolean verifyHistory(ServiceProvision value, long now) {
        for (var settlement : value.settlements()) {
            var attempt = settlement.attempt(); var physicalRecord = physical.find(attempt.intent().id());
            if (physicalRecord.isEmpty() || !physicalRecord.get().intent().equals(attempt.intent())) { problems.put(value.terms().id(), "MISSING_OR_FOREIGN_SERVICE_ITEM_HISTORY"); return false; }
            var record = physicalRecord.get();
            if (record.phase() != (settlement.applied() ? PhysicalItemOperation.Phase.APPLIED : PhysicalItemOperation.Phase.DENIED)
                    || record.proof().orElseThrow().occurredAt() != settlement.occurredAt()
                    || settlement.applied() && !record.witness().equals(rights.itemOperationWitness(attempt.intent().id()))) {
                problems.put(value.terms().id(), "SERVICE_ITEM_HISTORY_REQUIRES_REVIEW"); return false;
            }
            // 历史共享域和同档营养已核实即可累计基础需求；真实主人凭据由下一次实物提交核对，不能为了核对强制加载身体。
            if (settlement.applied() && attempt.purpose() == ServiceProvision.Purpose.MEAL) {
                var meal = life.mealReceipt(attempt.intent().id());
                if (meal == null || !meal.sameEffect(value.terms().person(), attempt.value(), "service-ration:" + value.terms().id(), settlement.occurredAt())) { problems.put(value.terms().id(), "SERVICE_NUTRITION_HISTORY_REQUIRES_REVIEW"); return false; }
                if (life.needs(value.terms().person()) == null || life.needs(value.terms().person()).lastUpdatedAt() < meal.appliedAt()) { problems.put(value.terms().id(), "SERVICE_NEEDS_HISTORY_REQUIRES_REVIEW"); return false; }
            }
        } return true;
    }
    private Reply persisted(ServiceProvision value) {
        var before=store.desired(value.terms().id()).flatMap(ServiceProvision::pending);var result = store.persist(value);
        if (value.ownsNeeds() && store.desired(value.terms().id()).filter(value::equals).isPresent()) rememberNeeds(value);
        if(result.status()==DurableSnapshotStore.Status.APPLIED||result.status()==DurableSnapshotStore.Status.PENDING){if(!before.equals(value.pending()))pendingChanged=true;if(value.pending().isPresent())settling.add(value.terms().id());else settling.remove(value.terms().id());}
        return reply(Status.valueOf(result.status().name()), null, result.reason());
    }
    private static Reply reply(Status status, ServiceProvision.Mark mark, String reason) { return new Reply(status, Optional.ofNullable(mark), reason); }
    @Override public void close() { ready.clear();settling.clear();pendingOwners.clear();store.close(); }
}
