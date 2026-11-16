package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;

/** 可信服务端的原生装配入口；默认不授予军事同意、军队权限、物权或空仓库。 */
public final class WarNativeMobilization implements Inspection, AutoCloseable {
    private final MinecraftServer server;
    private final WarCandidateInspector inspector;
    private final WarCandidateInspector.Sources sources;
    private final WorkCommitmentService work;
    private final ServiceProvisionService provisions;
    private final WarKitAdapter kits;
    private final WarLaborAdapter labor;
    private final WarProvisionAdapter costs;
    private final BiFunction<UUID, UUID, WarCommandService.Permission> authority;
    private java.util.function.BiPredicate<WarMilitiaRequest,Long> additionalConsent=(request,now)->true;
    public void bindConsent(java.util.function.BiPredicate<WarMilitiaRequest,Long> gate){additionalConsent=Objects.requireNonNull(gate);}
    public boolean permits(WarMilitiaRequest request,long now){
        var binding=sources.characters().bodyOf(request.characterId());var life=sources.life().record(request.characterId());
        return binding!=null&&life!=null&&sources.services().permits(request.actorId(),request.characterId(),WarCandidateInspector.REQUIRED_SCOPES,true,
                binding.dimensionId(),life.activeWorkplaceSettlementId(),now)&&additionalConsent.test(request,now);
    }
    private WarNativeMobilization(MinecraftServer server, WarCandidateInspector.Sources sources,
                                  BiFunction<UUID, UUID, WarCommandService.Permission> authority) throws IOException {
        this.server = Objects.requireNonNull(server); this.sources = Objects.requireNonNull(sources); this.authority = Objects.requireNonNull(authority);
        inspector = new WarCandidateInspector(server, sources, InitialWarDefinitions.LOADOUTS);
        work = WorkCommitmentRuntime.activate(server); provisions = ServiceProvisionRuntime.activate(server);
        var physical = PhysicalItemRuntime.activate(server);
        kits = new WarKitAdapter(server.getWorldPath(LevelResource.ROOT).resolve("data/" + WarKitAdapter.FILE_NAME), physical, RightsStateData.get(server),
                location -> location.resolve(server, sources.characters()), plan -> ItemLocation.equipment(plan.request().characterId(), plan.body()).body(server, sources.characters())
                .map(npc -> WarEquipmentInspector.inspect(npc, InitialWarDefinitions.LOADOUTS.find(plan.request().loadout()).orElseThrow().primaryWeapon())));
        labor = new WarLaborAdapter(work, inspector); costs = new WarProvisionAdapter(provisions);
    }
    public static WarNativeMobilization bind(MinecraftServer server, WarMobilizationRuntime runtime, WarCandidateInspector.Sources sources,
                                             BiFunction<UUID, UUID, WarCommandService.Permission> authority) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("native military binding requires server thread");
        var nativePorts = new WarNativeMobilization(server, sources, authority);
        try {
            Map<WarMobilization.Stage, EffectPort> effects = new EnumMap<>(WarMobilization.Stage.class);
            for (var stage : List.of(WarMobilization.Stage.CLAIM, WarMobilization.Stage.RELEASE_LABOR)) effects.put(stage, nativePorts.labor);
            effects.put(WarMobilization.Stage.CLAIM,nativePorts.consented(nativePorts.labor));
            effects.put(WarMobilization.Stage.ACTIVATE, nativePorts.activation());
            for (var stage : List.of(WarMobilization.Stage.OPEN_PAYROLL, WarMobilization.Stage.SUSTAIN, WarMobilization.Stage.END_SUSTAIN, WarMobilization.Stage.CLOSE_PAYROLL)) effects.put(stage, nativePorts.costs);
            effects.put(WarMobilization.Stage.EQUIP, nativePorts.kits); effects.put(WarMobilization.Stage.RETURN_EQUIPMENT, nativePorts.returnEquipment());
            runtime.bind(nativePorts, effects); runtime.bindCandidateInspector(nativePorts.inspector); return nativePorts;
        } catch (IOException | RuntimeException exception) { nativePorts.close(); throw exception; }
    }
    public int protocolVersion() { return 1; }
    public boolean responsibilitiesPermitClaim(WarMilitiaRequest request){return inspector.originalResponsibilitiesPermitClaim(request.characterId(),request.id());}
    /** 明确供给配置与真实装备清单。返回APPLIED后才可提交动员；重试保留原内容。 */
    public Status configure(WarMilitiaRequest request, ItemLocation treasury, ItemLocation armory, List<WarKitRecord.Line> kit) {
        if (!server.isSameThread()) throw new IllegalStateException("military configuration requires server thread");
        if (!permits(request,server.overworld().getGameTime()) || authority.apply(request.actorId(), request.armyId()) != WarCommandService.Permission.ALLOWED
                || !com.jgeted.sagadyssey.social.war.domain.WarServiceRoles.supported(request)) return Status.UNAVAILABLE;
        var currency = net.minecraft.resources.Identifier.parse(request.wage().currency().toString());
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(currency) || net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(currency) == net.minecraft.world.item.Items.AIR) return Status.UNAVAILABLE;
        var old = kits.find(request.id()); var report = inspector.inspect(List.of(request), server.overworld().getGameTime()).getFirst();
        if (old.isEmpty() && !report.observedFactsClear()) return Status.UNAVAILABLE;
        var body = old.map(WarKitRecord::body).or(() -> inspector.registeredBody(request.characterId())); if (body.isEmpty()) return Status.UNAVAILABLE;
        var terms = new ServiceProvision.Terms(request.id(), "sagadyssey:war", request.armyId(), request.characterId(), body.get(), SubjectRef.character(request.actorId()),
                treasury, new ItemFingerprint(request.wage().currency().toString(), 0), request.wage().amountPerPeriod(), request.wage().periodTicks());
        var cost = provisions.configure(terms, server.overworld().getGameTime());
        if (cost.status() == ServiceProvisionService.Status.CONFLICT || cost.status() == ServiceProvisionService.Status.UNKNOWN) return Status.UNKNOWN;
        if (cost.status() == ServiceProvisionService.Status.UNAVAILABLE) return Status.UNAVAILABLE;
        var equipment = kits.configure(new WarKitRecord(request, body.get(), armory, kit, List.of(), List.of()));
        if (cost.status() != ServiceProvisionService.Status.APPLIED || equipment != Status.APPLIED) return equipment == Status.UNKNOWN ? Status.UNKNOWN : Status.PENDING;
        return Status.APPLIED;
    }
    public Readiness inspect(WarMilitiaRequest request, long now) {
        var reasons = new ArrayList<String>();
        if(!permits(request,now))reasons.add("CURRENT_SERVICE_CONSENT_REQUIRED");
        var observed = inspector.inspect(List.of(request), now).getFirst(); observed.problems().forEach(problem -> reasons.add(problem.code()));
        var plan = kits.find(request.id()); var cost = provisions.find(request.id());
        if (plan.isEmpty() || !plan.get().request().equals(request) || cost.isEmpty() || !matches(cost.get().terms(), request, plan.get().body())) reasons.add("PERSISTENT_SUPPLY_AND_KIT_PLAN_REQUIRED");
        else if (!kits.durable(request.id()) || !provisions.durable(request.id())) reasons.add("SUPPLY_CONFIGURATION_NOT_YET_DURABLE");
        return new Readiness(reasons.isEmpty(), reasons);
    }
    /** 仅遍历有界持久索引。共享端允许比军档领先当前一步，拒绝孤立义务和多步越序。 */
    public Readiness reconcile(Collection<WarMobilization> records) {
        var byId = new HashMap<UUID, WarMobilization>(); for (var record : records) byId.put(record.request().id(), record);
        var reasons = new ArrayList<String>();
        for (var shared : work.records()) if (shared.intent().ownerKind().equals("sagadyssey:war") && shared.holdsPerson() && !byId.containsKey(shared.intent().id())) reasons.add("ORPHAN_WAR_LABOR:" + shared.intent().id());
        for (var cost : provisions.records()) if (cost.terms().ownerKind().equals("sagadyssey:war") && !cost.marks().isEmpty()) {
            var record = byId.get(cost.terms().id());
            if (record == null || !matches(cost.terms(), record.request(), cost.terms().body())) { reasons.add("ORPHAN_OR_FOREIGN_WAR_PROVISION:" + cost.terms().id()); continue; }
            for (var mark : cost.marks().entrySet()) {
                var stage = WarMobilization.Stage.valueOf(mark.getKey().name());
                if (!mark.getValue().operation().equals(WarMobilization.operationId(record.request().id(), stage))
                        || !record.receipts().containsKey(stage) && record.stage() != stage) reasons.add("PROVISION_AHEAD_OF_WAR_JOURNAL:" + cost.terms().id());
            }
        }
        for (var plan : kits.records()) if (plan.issue().isPresent()) {
            var record = byId.get(plan.request().id());
            if (record == null || !record.request().equals(plan.request()) || !record.receipts().containsKey(WarMobilization.Stage.EQUIP)
                    && record.stage() != WarMobilization.Stage.EQUIP && !kits.provedUnapplied(plan, false)) reasons.add("ORPHAN_OR_AHEAD_KIT:" + plan.request().id());
            if (!kits.priorAttemptsProvedUnapplied(plan)) reasons.add("KIT_RETRY_PROOF_MISSING:" + plan.request().id());
        }
        for (var record : records) {
            var request = record.request(); var shared = work.find(request.id()); var plan = kits.find(request.id()); var cost = provisions.find(request.id());
            if (record.receipts().containsKey(WarMobilization.Stage.CLAIM) && (shared.isEmpty() || !shared.get().intent().character().equals(request.characterId())
                    || plan.isEmpty() || !shared.get().intent().body().equals(plan.get().body()) || !shared.get().intent().owner().equals(request.armyId())
                    || !shared.get().intent().principal().equals(request.actorId()) || !shared.get().intent().descriptorHash().equals(WarLaborAdapter.fingerprint(request)))) reasons.add("WAR_LABOR_EVIDENCE_MISSING:" + request.id());
            for (var stage : List.of(WarMobilization.Stage.OPEN_PAYROLL, WarMobilization.Stage.SUSTAIN, WarMobilization.Stage.END_SUSTAIN, WarMobilization.Stage.CLOSE_PAYROLL)) {
                if (record.receipts().containsKey(stage)) {
                    var mark = cost.map(value -> value.marks().get(WarProvisionAdapter.step(stage))).orElse(null); var receipt = record.receipts().get(stage);
                    if (mark == null || !mark.operation().equals(receipt.operationId()) || mark.acknowledgedAt() != receipt.recordedAtTick() || mark.outstanding() != receipt.outstandingObligation()) reasons.add("WAR_PROVISION_EVIDENCE_MISSING:" + request.id());
                }
            }
            if (record.receipts().containsKey(WarMobilization.Stage.EQUIP) && (plan.isEmpty() || !plan.get().lines().isEmpty() && plan.get().issue().isEmpty())) reasons.add("WAR_KIT_EVIDENCE_MISSING:" + request.id());
            for (var stage : List.of(WarMobilization.Stage.EQUIP, WarMobilization.Stage.RETURN_EQUIPMENT)) if (record.receipts().containsKey(stage)
                    && (plan.isEmpty() || !kits.confirms(record.receipts().get(stage), plan.get()))) reasons.add("WAR_KIT_RECEIPT_MISSING:" + request.id());
        }
        if (reasons.size() > 64) reasons = new ArrayList<>(reasons.subList(0, 64)); return new Readiness(reasons.isEmpty(), reasons);
    }
    private EffectPort consented(EffectPort port){return new EffectPort(){
        public int protocolVersion(){return port.protocolVersion();}
        public Reply lookup(Effect e){return port.lookup(e);}
        public Reply apply(Effect e){return permits(e.request(),e.now())?port.apply(e):Reply.state(Status.UNAVAILABLE,"CURRENT_SERVICE_CONSENT_REQUIRED");}
        public Reply cancel(Effect e){return port.cancel(e);}
    };}
    private EffectPort activation() {
        return new EffectPort() {
            public int protocolVersion() { return 1; }
            public Reply lookup(Effect effect) { return labor.lookup(effect); }
            public Reply apply(Effect effect) {
                if(!permits(effect.request(),effect.now()))return Reply.state(Status.UNAVAILABLE,"CURRENT_SERVICE_CONSENT_REQUIRED");
                if(!kits.equipped(effect.request().id())) return Reply.state(Status.UNAVAILABLE,"ACTUAL_KIT_DOES_NOT_MATCH_LOADOUT");
                var request=effect.request();var binding=sources.characters().bodyOf(request.characterId());
                var body=binding==null?Optional.<com.jgeted.sagadyssey.npc.entity.NpcBase>empty():ItemLocation.personal(request.characterId(),binding.entityId()).body(server,sources.characters());
                if(body.isEmpty())return Reply.state(Status.UNAVAILABLE,"ORIGINAL_MOBILITY_BODY_UNAVAILABLE");
                var mobility=WarNativeBindings.currentBuild().mobility().prepare(body.get(),request);
                return mobility.usable()?labor.apply(effect):Reply.state(mobility.mode()==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.REVIEW?Status.UNKNOWN:Status.UNAVAILABLE,mobility.reason());
            }
            public Reply cancel(Effect effect) { return labor.cancel(effect); }
        };
    }
    /** 先恢复借用的原主武器；v2领退记录仍只针对原装备容器，不猜测背包里的同名私物。 */
    private EffectPort returnEquipment() {
        return new EffectPort() {
            public int protocolVersion() { return 1; }
            private Reply restore(Effect effect) {
                var plan = kits.find(effect.request().id());
                if (plan.isEmpty() || plan.get().returned().isPresent()) return null;
                var body = ItemLocation.equipment(effect.request().characterId(), plan.get().body()).body(server, sources.characters());
                if (body.isEmpty()) return Reply.state(Status.UNAVAILABLE, "ORIGINAL_WEAPON_BODY_UNAVAILABLE");
                var primary = WarAttackKind.primary(InitialWarDefinitions.LOADOUTS.find(effect.request().loadout()).orElseThrow().primaryWeapon());
                var result = WarNativeBindings.currentBuild().actors().equip(body.get(), effect.request().id(), primary, primary);
                return switch (result.status()) { case READY -> null; case PENDING -> Reply.state(Status.PENDING, result.reason());
                    case BLOCKED -> Reply.state(Status.UNAVAILABLE, result.reason()); case REVIEW -> Reply.state(Status.UNKNOWN, result.reason()); };
            }
            public Reply lookup(Effect effect) { var pending = restore(effect); return pending == null ? kits.lookup(effect) : pending; }
            public Reply apply(Effect effect) { var pending = restore(effect); return pending == null ? kits.apply(effect) : pending; }
            public Reply cancel(Effect effect) { return lookup(effect); }
        };
    }
    private static boolean matches(ServiceProvision.Terms terms, WarMilitiaRequest request, UUID body) {
        return terms.ownerKind().equals("sagadyssey:war") && terms.id().equals(request.id()) && terms.owner().equals(request.armyId()) && terms.person().equals(request.characterId())
                && terms.body().equals(body) && terms.payer().equals(SubjectRef.character(request.actorId())) && terms.wageItem().equals(new ItemFingerprint(request.wage().currency().toString(), 0))
                && terms.rate() == request.wage().amountPerPeriod() && terms.period() == request.wage().periodTicks();
    }
    public ServiceProvisionService provisions() { return provisions; }
    public WarKitAdapter kits() { return kits; }
    @Override public void close() { kits.close(); }
}
