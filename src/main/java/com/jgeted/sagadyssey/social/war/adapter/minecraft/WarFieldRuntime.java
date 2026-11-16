package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.runtime.WarWorkQueue;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts;
import com.jgeted.sagadyssey.social.war.spi.WarBattlePorts;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** M3-a近场：收到且持久化的军令驱动原身体；重规划和感知分别走世界公平预算。 */
public final class WarFieldRuntime implements NpcPrimaryWorkControl, AutoCloseable {
    public record Sources(CharacterRegistryData characters, InjuryData injuries, CombatEncounterData encounters, NpcLifeData life) {
        public Sources { Objects.requireNonNull(characters); Objects.requireNonNull(injuries); Objects.requireNonNull(encounters); Objects.requireNonNull(life); }
    }
    private static final String OWNER = "sagadyssey:war";
    private static final class Motion {
        final UUID contract, order;
        final WarIntent.Position station;
        final MovementProgress progress = new MovementProgress();
        UUID target;
        long nextPath, nextSense;
        int senseOffset;
        long drawingSince = -1, nextAmmoCheck;
        UUID drawingTarget;
        WarAttackKind drawingKind;
        boolean hasAmmo;
        double engagementRange = 8, stationRange = 6;
        boolean following;
        boolean pathFailed;
        boolean withdrawing, retreatRecorded;
        WarIntent.Position movementGoal;
        long cavalryWave=-1;
        boolean cavalryRun;
        WarIntent.Position cavalryDestination;
        Motion(UUID contract, UUID order, WarIntent.Position station) { this.contract = contract; this.order = order; this.station = station; }
    }
    private final MinecraftServer server;
    private final WarServerRuntime core;
    private final WarMobilizationRuntime militia;
    private final Sources sources;
    private final WarCommandService.Authority authority;
    private final WarNativeBindings nativeBindings;
    private final WarWorkQueue paths = new WarWorkQueue(new WarWorkQueue.Limits(2048, 4, 1, 2_000_000), System::nanoTime);
    private final WarWorkQueue senses = new WarWorkQueue(new WarWorkQueue.Limits(2048, 4, 1, 2_000_000), System::nanoTime);
    private final Map<UUID, Motion> motions = new HashMap<>();
    private final Map<UUID, WarFieldReport> reports = new HashMap<>();
    private final Map<UUID, String> problems = new HashMap<>();
    private final LinkedHashSet<UUID> dispatchReady = new LinkedHashSet<>();
    private WarDispatchPorts.Journal journal;
    private WarDispatchService dispatches;
    private WarCohesionRuntime cohesion;
    private final WarLeadershipRuntime leadership;
    private WarCavalryRuntime cavalry;
    private WarFarRuntime far;
    private WarBattlePorts.Engagement<NpcBase> engagement;
    private boolean ordinaryBattleHost;
    private long nextArchiveCheckpoint;
    private String blocked = "";
    private boolean closed;
    public WarFieldRuntime(MinecraftServer server, WarServerRuntime core, WarMobilizationRuntime militia, Sources sources, WarCommandService.Authority authority,
                           WarNativeBindings nativeBindings, WarDispatchPorts.JournalFactory journals,
                           com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.JournalFactory tacticalJournals,
                           com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Policy tacticalPolicy,
                           com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.JournalFactory cavalryJournals,
                           com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Policy cavalryPolicy) {
        this.server = Objects.requireNonNull(server); this.core = Objects.requireNonNull(core); this.militia = Objects.requireNonNull(militia);
        this.sources = Objects.requireNonNull(sources); this.authority = Objects.requireNonNull(authority); check();
        this.engagement=new WarExistingEngagement(sources.encounters());
        this.nativeBindings = Objects.requireNonNull(nativeBindings);
        this.leadership = new WarLeadershipRuntime(server);
        try {
            journal = Objects.requireNonNull(journals).open();
            dispatches = new WarDispatchService(new WarDispatchService.Organization() {
                public WarCommandService.Permission authorize(UUID actor, UUID army) { return authority.check(actor, army); }
                public WarResult submit(WarCommand.IssueOrder command, long now) { return core.submit(command); }
                public WarResult deliver(UUID unit, long generation, UUID order, long now) { return core.deliver(unit, generation, order); }
                public Optional<WarUnit> unit(UUID id) { return core.session().flatMap(session -> session.unit(id)); }
            }, journal);
            for (var value : dispatches.records()) dispatchReady.add(value.command().operationId());
            cohesion = new WarCohesionRuntime(server,core,militia,sources,CombatMoraleData.get(server),
                    (unit,now)->dispatches.effective(unit,now).filter(value->authority.check(value.command().actorId(),value.command().armyId())==WarCommandService.Permission.ALLOWED),
                    this::active,tacticalJournals,tacticalPolicy,nativeBindings.mobility(),leadership);
            cavalry=new WarCavalryRuntime(server,core,militia,sources,nativeBindings,cohesion,
                    (unit,now)->dispatches.effective(unit,now).filter(value->authority.check(value.command().actorId(),value.command().armyId())==WarCommandService.Permission.ALLOWED),this::active,cavalryJournals,cavalryPolicy,dispatches.records());
            if (WarFarRuntime.present(server)) far = new WarFarRuntime(server,core,this,sources);
        } catch (IOException | RuntimeException exception) {
            if (far != null) far.close();
            far = null;
            if(cavalry!=null)cavalry.close();cavalry=null;
            if (cohesion != null) cohesion.close(); cohesion = null;
            if (journal != null) journal.close(); journal = null; dispatches = null;
            blocked = "FIELD_JOURNAL_REQUIRES_REVIEW:" + exception.getClass().getSimpleName();
        }
        // 即使日志损坏也绑定安全等待控制；不把持有主劳动的人物放回旧的无限追击AI。
        NpcPrimaryWorkRuntime.register(server, OWNER, this);
    }
    /** Explicit ordinary-world assembly; legacy/trusted hosts retain read-only original encounter behavior. */
    public void bindOrdinaryBattleHost(){
        check();if(ordinaryBattleHost||closed)throw new IllegalStateException("battle host already bound or closed");
        engagement=new WarBattleRuntime(server,sources,this::battleService,person->militia.activePerson(person).map(WarMobilization::request),militia::activeUnit);ordinaryBattleHost=true;
    }
    private Optional<WarMilitiaRequest> battleService(NpcBase npc,long now){
        if(!active()||dispatches==null||!registered(npc))return Optional.empty();
        var service=militia.activePerson(npc.getCharacterId());if(service.isEmpty())return Optional.empty();var r=service.get().request();
        if(farHolds(r.unitId())||!core.servicePermits(r,now)||authority.check(r.actorId(),r.armyId())!=WarCommandService.Permission.ALLOWED)return Optional.empty();
        return dispatches.effective(r.unitId(),now).filter(i->i.command().armyId().equals(r.armyId())
                &&authority.check(i.command().actorId(),r.armyId())==WarCommandService.Permission.ALLOWED
                &&dispatches.memberSlot(i,new WarTransmission.Member(r.id(),r.characterId(),npc.getUUID()))>=0).map(i->r);
    }
    public WarDispatchService.Result dispatch(WarCommand.IssueOrder command) {
        return dispatch(command,false);
    }
    public WarDispatchService.Result dispatchLocalSignal(WarCommand.IssueOrder command){return dispatch(command,true);}
    private WarDispatchService.Result dispatch(WarCommand.IssueOrder command,boolean local) {
        check();
        if (authority.check(command.actorId(), command.armyId()) != WarCommandService.Permission.ALLOWED) return rejected(WarDispatchService.Outcome.DENIED, "ARMY_AUTHORITY_REQUIRED");
        if (dispatches == null || closed || !active()) return rejected(WarDispatchService.Outcome.UNAVAILABLE, blocked.isEmpty() ? "WAR_NOT_ACTIVE" : blocked);
        var prior = dispatches.find(command.operationId());
        if (prior.isPresent()) return new WarDispatchService.Result(prior.get().command().equals(command) ? WarDispatchService.Outcome.REPLAYED : WarDispatchService.Outcome.CONFLICT, prior, "");
        if (!Set.of(InitialWarDefinitions.MOVE,InitialWarDefinitions.HOLD,InitialWarDefinitions.RETREAT,InitialWarDefinitions.RALLY,InitialWarDefinitions.CHARGE).contains(command.intent().kind())) return rejected(WarDispatchService.Outcome.UNAVAILABLE, "FIELD_ORDER_CAPABILITY_UNAVAILABLE");
        long now = server.overworld().getGameTime(); var roster = militia.activeUnit(command.unitId()).stream().filter(v->v.request().role()==WarMilitiaRequest.Role.COMBATANT).toList();
        if (roster.isEmpty() || roster.size() > WarTransmission.MAX_MEMBERS || roster.stream().anyMatch(value -> !value.request().armyId().equals(command.armyId()))) return rejected(WarDispatchService.Outcome.UNAVAILABLE, "ACTIVE_ROSTER_REQUIRED");
        for (var value : roster) { if(!core.servicePermits(value.request(),server.overworld().getGameTime()))return rejected(WarDispatchService.Outcome.DENIED,"CURRENT_SERVICE_CONSENT_REQUIRED");var support = fieldSupport(value.request()); if (!support.implemented()) return rejected(WarDispatchService.Outcome.UNAVAILABLE, support.reason()); }
        if(command.intent().kind().equals(InitialWarDefinitions.CHARGE)&&roster.stream().anyMatch(v->InitialWarDefinitions.LOADOUTS.find(v.request().loadout()).filter(p->p.primaryWeapon()==WarLoadout.Weapon.SPEAR||p.mount()!=WarLoadout.Mount.FOOT&&p.primaryWeapon()==WarLoadout.Weapon.SWORD).isEmpty()))return rejected(WarDispatchService.Outcome.UNAVAILABLE,"SPEAR_OR_MOUNTED_SWORD_ROSTER_REQUIRED_FOR_CHARGE");
        Map<UUID, UUID> originalBodies = new HashMap<>();
        for (var value : roster) {
            var binding = sources.characters().bodyOf(value.request().characterId()); var character = sources.characters().byId(value.request().characterId());
            if (binding == null || character == null || !character.alive()) return rejected(WarDispatchService.Outcome.UNAVAILABLE, "ROSTER_LIFECYCLE_REQUIRES_RECONCILIATION");
            originalBodies.put(value.request().characterId(), binding.entityId());
        }
        var origin = issuer(command.actorId()); if (origin.isEmpty()) return rejected(WarDispatchService.Outcome.UNAVAILABLE, "REGISTERED_ISSUER_BODY_REQUIRED");
        var route=local?localRoute(command.actorId(),command.armyId(),command.unitId()):route(command.actorId(),command.armyId(),command.unitId());
        if(route.isEmpty()) return rejected(WarDispatchService.Outcome.UNAVAILABLE,"RECENT_FRIENDLY_REPORT_OR_ROUTE_REQUIRED");
        if(command.intent().kind().equals(InitialWarDefinitions.CHARGE))try{WarCavalryFormation.validate(cohesion.state(command.unitId()).map(WarCohesion::rally).orElse(route.get().origin()),command.intent().target());}catch(IllegalArgumentException e){return rejected(WarDispatchService.Outcome.UNAVAILABLE,"CHARGE_APPROACH_REQUIRES_8_TO_48_BLOCKS");}
        var target = command.intent().target(); var level = origin.get().level() instanceof ServerLevel l ? l : null;
        if (level == null || !level.dimension().identifier().toString().equals(target.dimension().toString()) || target.y() < level.getMinY() || target.y() >= level.getMaxY() + 1
                || Math.abs((long)target.x()) > 29_999_900 || Math.abs((long)target.z()) > 29_999_900) return rejected(WarDispatchService.Outcome.UNAVAILABLE, "INVALID_OR_CROSS_DIMENSION_TARGET");
        try {
            var members = roster.stream().map(value -> new WarTransmission.Member(value.request().id(), value.request().characterId(), originalBodies.get(value.request().characterId()))).toList();
            var result = dispatches.begin(command, route.get(), members, now);
            if (result.outcome() == WarDispatchService.Outcome.ACCEPTED) dispatchReady.add(command.operationId()); return result;
        } catch (IllegalArgumentException exception) { return rejected(WarDispatchService.Outcome.UNAVAILABLE, "TRANSMISSION_ROUTE_UNAVAILABLE"); }
    }
    /** Same deterministic own-report route for preview and submission. No enemy observations or forced loads. */
    public Optional<WarTransmission.Route> route(UUID actor,UUID army,UUID unit){
        return route(actor,army,unit,1200);
    }
    public Optional<WarTransmission.Route> localRoute(UUID actor,UUID army,UUID unit){return route(actor,army,unit,40).filter(r->WarCommandMediaPolicy.audible(r,server.overworld().getGameTime()));}
    private Optional<WarTransmission.Route> route(UUID actor,UUID army,UUID unit,long maximumAge){
        check();if(!active() || dispatches==null || authority.check(actor,army)!=WarCommandService.Permission.ALLOWED)return Optional.empty();
        var origin=issuer(actor);if(origin.isEmpty())return Optional.empty();var p=position(origin.get());long now=server.overworld().getGameTime();
        return militia.activeUnit(unit).stream().filter(v->v.request().armyId().equals(army)).map(v->{
            var r=reports.get(v.request().characterId());var b=sources.characters().bodyOf(v.request().characterId());
            return r!=null && b!=null && r.contract().equals(v.request().id()) && r.body().equals(b.entityId()) && r.position().dimension().equals(p.dimension()) && now>=r.observedAt() && now-r.observedAt()<=maximumAge?r:null;
        }).filter(Objects::nonNull).sorted(Comparator.<WarFieldReport>comparingDouble(r->{double x=(long)p.x()-r.position().x(),y=(long)p.y()-r.position().y(),z=(long)p.z()-r.position().z();return x*x+y*y+z*z;})
                .thenComparing(Comparator.comparingLong(WarFieldReport::observedAt).reversed()).thenComparing(WarFieldReport::character))
                .findFirst().flatMap(r->{try{return Optional.of(WarTransmission.route(p,r.position(),r.observedAt(),now));}catch(IllegalArgumentException e){return Optional.empty();}});
    }
    public Optional<WarTransmission> transmission(UUID actor, UUID army, UUID order) {
        check(); if (dispatches == null || authority.check(actor, army) != WarCommandService.Permission.ALLOWED) return Optional.empty();
        return dispatches.find(order).filter(value -> value.command().armyId().equals(army));
    }
    boolean checkpointed(WarTransmission transmission) {
        check();
        return dispatches != null && dispatches.checkpointed(transmission);
    }
    public List<WarFieldReport> reports(UUID actor, UUID army) {
        check(); if (authority.check(actor, army) != WarCommandService.Permission.ALLOWED) return List.of();
        return reports.values().stream().filter(report -> militia.activePerson(report.character()).filter(value -> value.request().armyId().equals(army) && value.request().id().equals(report.contract())).isPresent()).sorted(Comparator.comparing(WarFieldReport::character)).toList();
    }
    public Map<String, WarWorkQueue.Snapshot> budgets() { check(); return cohesion==null?Map.of("paths", paths.snapshot(), "perception", senses.snapshot()):Map.of("paths", paths.snapshot(), "perception", senses.snapshot(),"cohesion",cohesion.budget(),"cavalry",cavalry.budget()); }
    public String leadershipSummary(UUID person) { check(); return leadership.summary(person); }
    public Optional<WarCavalry> cavalry(UUID actor,UUID army,UUID unit){check();return cavalry==null||authority.check(actor,army)!=WarCommandService.Permission.ALLOWED?Optional.empty():cavalry.state(unit).filter(s->s.army().equals(army));}
    public Optional<WarCohesion> cohesion(UUID actor,UUID army,UUID unit) {
        check(); if(cohesion==null || authority.check(actor,army)!=WarCommandService.Permission.ALLOWED) return Optional.empty();
        return cohesion.state(unit).filter(state->state.army().equals(army));
    }
    public Optional<String> problem(UUID order) { check(); return Optional.ofNullable(problems.get(order)); }
    public String blockedReason(){check();return blocked;}
    public Optional<WarDispatchService.History> history() { check(); return Optional.ofNullable(dispatches).map(WarDispatchService::history); }
    Optional<WarTransmission> received(UUID unit,long now) {
        check();
        return dispatches == null || !active() ? Optional.empty() : dispatches.effective(unit,now);
    }
    public Optional<WarFarRuntime> far() { check(); return Optional.ofNullable(far); }
    public WarFarRuntime openFar() throws IOException {
        check();
        if (dispatches == null || !active()) throw new IOException("军事执行暂不可用");
        if (far == null) far = new WarFarRuntime(server,core,this,sources);
        return far;
    }
    private boolean farHolds(UUID unit) { return far != null && far.holds(unit); }
    public void tick() {
        check(); if (closed) return; long now = server.overworld().getGameTime();
        if (dispatches != null && active()) for (int n = Math.min(4, dispatchReady.size()); n > 0; n--) {
            var iterator = dispatchReady.iterator(); UUID id = iterator.next(); iterator.remove();
            var result = dispatches.advance(id, now);
            if (result.outcome() == WarDispatchService.Outcome.WAITING) dispatchReady.add(id);
            else if (result.outcome() == WarDispatchService.Outcome.REVIEW) problems.put(id, result.reason());
        }
        if (far != null && active()) far.tick(now);
        if (dispatches != null && active() && now % 40 == 0) maintainHistory(now);
        leadership.tick();if(cohesion!=null) cohesion.tick(now);if(cavalry!=null)cavalry.tick(now); paths.tick(now); senses.tick(now);
    }
    private void maintainHistory(long now) {
        var muster = WarMusterRuntime.existing(server);
        if (muster.isEmpty()) return;
        var owner = muster.orElseThrow();
        var saved = owner.savedCore();
        if (dispatches.history().hotRecords() >= WarDispatchService.ARCHIVE_HIGH_WATER && now >= nextArchiveCheckpoint) {
            owner.requestCoreCheckpoint();
            nextArchiveCheckpoint = now + 600;
        }
        var retired = dispatches.maintain(now, row -> row.command().intent().kind().equals(InitialWarDefinitions.CHARGE)
                || saved.map(s -> s.units().get(row.command().unitId()))
                .filter(u -> u.armyId().equals(row.command().armyId()))
                .flatMap(WarUnit::currentOrder).filter(order -> order.sequence() > row.sequence()).isEmpty());
        for (var id : retired) {
            dispatchReady.remove(id);
            problems.remove(id);
        }
    }

    @Override public void tick(NpcBase npc, WorkCommitment work, long now) {
        var telemetry = core.telemetry();
        long started = telemetry.start();
        try { execute(npc, work, now); }
        finally { telemetry.field(now, started); }
    }
    private void execute(NpcBase npc, WorkCommitment work, long now) {
        check(); var service = militia.activePerson(npc.getCharacterId());
        if (closed || dispatches == null || !active() || work.phase() != WorkCommitment.Phase.ACTIVE || service.isEmpty() || !service.get().request().id().equals(work.intent().id())) {
            wait(npc, work, WarFieldReport.State.WAITING, blocked.isEmpty() ? "WAITING_FOR_DURABLE_ACTIVE_SERVICE" : blocked, now); return;
        }
        var request = service.get().request();
        if (request.role() == WarMilitiaRequest.Role.COMBATANT && farHolds(request.unitId())) {
            wait(npc, work, WarFieldReport.State.WAITING, "FAR_READINESS_EXECUTION_HANDOFF", now);
            return;
        }
        if(request.role()==WarMilitiaRequest.Role.ENGINEER){
            try{report(npc,work,null,WarFieldReport.State.WAITING,"ORIGINAL_ENGINEERING_MAIN_DUTY",now);WarEngineeringRuntime.open(server).work(npc,work,now);}
            catch(Exception unavailable){wait(npc,work,WarFieldReport.State.REVIEW,"ORIGINAL_ENGINEERING_REQUIRES_REVIEW",now);}
            return;
        }
        if(request.role()==WarMilitiaRequest.Role.STAFF&&!npc.getTransportInventory().isEmpty()&&WarCampaignRuntime.existing(server).isEmpty()){wait(npc,work,WarFieldReport.State.REVIEW,"ORIGINAL_CARGO_WITHOUT_CAMPAIGN_RECORD",now);return;}
        if(WarCampaignRuntime.existing(server).filter(r->r.work(npc,work,now)).isPresent()){
            nativeBindings.actors().clearTarget(npc);nativeBindings.actors().cancelRangedUse(npc);motions.remove(npc.getUUID());paths.cancel(npc.getUUID());senses.cancel(npc.getUUID());
            report(npc,work,null,WarFieldReport.State.WAITING,"ORIGINAL_CAMPAIGN_MAIN_DUTY",now);return;
        }
        if (authority.check(request.actorId(), request.armyId()) != WarCommandService.Permission.ALLOWED || !core.servicePermits(request,now)) { if(cohesion!=null)cohesion.suspendUnit(request.unitId());if(cavalry!=null)cavalry.suspend(request.unitId()); wait(npc, work, WarFieldReport.State.BLOCKED, "ARMY_AUTHORITY_OR_SERVICE_CONSENT_UNAVAILABLE", now); return; }
        if(WarStaffRelayRuntime.existing(server).filter(r->r.work(npc,work,now)).isPresent()){
            wait(npc,work,WarFieldReport.State.WAITING,"STAFF_RELAY_PRIMARY_DUTY",now);return;
        }
        if(request.role()==WarMilitiaRequest.Role.STAFF){
            if(WarStaffRuntime.existing(server).filter(s->s.standardDuty(npc,work,now)).isPresent()){report(npc,work,null,WarFieldReport.State.WAITING,"ORIGINAL_STANDARD_BEARER_DUTY",now);return;}
            stop(npc);var health=sources.injuries().observeTriage(npc.getCharacterId());var needs=sources.life().needs(npc.getCharacterId());
            boolean available=registered(npc)&&npc.isAlive()&&!npc.isNoAi()&&!npc.isSleeping()&&!npc.isInWater()&&!npc.isPassenger()&&!health.incapacitated()&&!health.mortalDanger()&&npc.getHealth()>npc.getMaxHealth()*.25F&&needs!=null&&needs.hunger()<900;
            var old=reports.get(npc.getCharacterId());
            if(old==null||now-old.observedAt()>=20||!available){
                long observed=militia.activeUnit(request.unitId()).stream().filter(v->v.request().role()==WarMilitiaRequest.Role.COMBATANT).map(v->reports.get(v.request().characterId())).filter(Objects::nonNull).filter(r->now>=r.observedAt()&&now-r.observedAt()<=200).count();
                report(npc,work,null,available?WarFieldReport.State.WAITING:WarFieldReport.State.BLOCKED,available?"DEDICATED_STAFF_REPORT_DUTY:RECENT_FRIENDLY_REPORTS="+observed:"STAFF_ORIGINAL_BODY_OR_NEEDS_UNAVAILABLE",now);
            }
            return;
        }
        var support = fieldSupport(request);
        if (!support.implemented()) { wait(npc, work, WarFieldReport.State.BLOCKED, support.reason(), now); return; }
        var expectedKind = WarAttackKind.primary(InitialWarDefinitions.LOADOUTS.find(request.loadout()).orElseThrow().primaryWeapon());
        var arms = nativeBindings.actors().arms(npc, request.id(), expectedKind);
        var observedWeapon = arms.held();
        var profile = observedWeapon.melee().filter(value -> value.kind() == expectedKind || arms.backupHeld() && value.kind() == WarAttackKind.SWORD_STRIKE);
        var ranged = observedWeapon.ranged().filter(value -> value.kind() == expectedKind);
        if (!registered(npc)) { wait(npc, work, WarFieldReport.State.BLOCKED, "ORIGINAL_REGISTERED_BODY_REQUIRED", now); return; }
        var mobility=nativeBindings.mobility().observe(npc,request);
        if(!mobility.usable()) { wait(npc,work,mobility.mode()==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.REVIEW?WarFieldReport.State.REVIEW:WarFieldReport.State.BLOCKED,mobility.reason(),now);return; }
        var health = sources.injuries().observeTriage(npc.getCharacterId()); var needs = sources.life().needs(npc.getCharacterId());
        if (health.incapacitated() || health.mortalDanger() || npc.getHealth() <= npc.getMaxHealth() * .25F || needs == null || needs.hunger() >= 900) { wait(npc, work, WarFieldReport.State.BLOCKED, "HEALTH_OR_ORIGINAL_NEEDS_PREVENT_ACTION", now); return; }
        if(npc.isPassenger())try{if(!WarCampaignRuntime.open(server).horse(npc,request)){wait(npc,work,WarFieldReport.State.WAITING,"ORIGINAL_HORSE_FEED_REQUIRED",now);return;}}catch(Exception unavailable){wait(npc,work,WarFieldReport.State.REVIEW,"ORIGINAL_HORSE_CARE_REQUIRES_REVIEW",now);return;}
        var effective = dispatches.effective(request.unitId(), now);
        if (effective.isEmpty()) { wait(npc, work, WarFieldReport.State.WAITING, "NO_DURABLE_RECEIVED_ORDER", now); return; }
        var instruction = effective.get(); var member = new WarTransmission.Member(request.id(), request.characterId(), npc.getUUID());
        int slot = dispatches.memberSlot(instruction, member);
        if (slot < 0) { wait(npc, work, WarFieldReport.State.WAITING, "MEMBER_NOT_IN_RECEIVED_ROSTER", now); return; }
        long recoveryTicks = Math.max(40, Math.max(profile.map(WarWeaponProfile.Melee::recoveryTicks).orElse(0L), arms.primaryRanged().map(WarWeaponProfile.Ranged::recoveryTicks).orElse(0L)));
        var checkpoint = checkpoint(npc, instruction, member, slot, now, recoveryTicks);
        if (checkpoint.isEmpty()) { wait(npc, work, WarFieldReport.State.REVIEW, "BODY_ORDER_CHECKPOINT_REQUIRES_REVIEW", now); return; }
        var existingMotion = motions.get(npc.getUUID());
        var station = existingMotion != null && existingMotion.order.equals(instruction.command().operationId())
                && existingMotion.contract.equals(member.contract()) ? existingMotion.station
                : instruction.station(slot, instruction.command().intent().target());
        if (!station.dimension().toString().equals(npc.level().dimension().identifier().toString())) { wait(npc, work, WarFieldReport.State.BLOCKED, "ORDER_DIMENSION_UNAVAILABLE", now); return; }
        if(instruction.command().intent().kind().equals(InitialWarDefinitions.CHARGE)){if(mobility.mode()==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.RIDING)cavalry.track(npc,instruction,member,now);else cavalry.dismounted(member.body());}
        cohesion.touch(instruction,now); var tactical=cohesion.control(instruction,member,now);
        if(tactical.status()!=com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.APPLIED) {
            var drawing=motions.get(npc.getUUID());
            if(tactical.status()==com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.PENDING&&drawing!=null
                    &&drawing.contract.equals(request.id())&&drawing.order.equals(instruction.command().operationId())
                    &&drawing.drawingSince>=0&&now>=drawing.drawingSince&&now-drawing.drawingSince<=200){
                // Continue holding the real item, not attacking. New strikes, movement and sensing stay fenced.
                nativeBindings.actors().stopNavigation(npc);nativeBindings.actors().clearTarget(npc);paths.cancel(npc.getUUID());senses.cancel(npc.getUUID());drawing.following=false;
                report(npc,work,instruction,WarFieldReport.State.WAITING,tactical.reason(),now);return;
            }
            wait(npc,work,tactical.status()==com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.UNKNOWN?WarFieldReport.State.REVIEW:WarFieldReport.State.WAITING,tactical.reason(),now); return;
        }
        var motion = motions.get(npc.getUUID());
        if (motion == null || !motion.contract.equals(request.id()) || !motion.order.equals(instruction.command().operationId())) {
            stop(npc); motion = new Motion(request.id(), instruction.command().operationId(), station); motions.put(npc.getUUID(), motion);
        }
        var frame = motion; frame.engagementRange = arms.primaryRanged().map(WarWeaponProfile.Ranged::maximumReach).orElse(8.0); frame.stationRange = arms.primaryRanged().map(WarWeaponProfile.Ranged::maximumReach).orElse(6.0);
        if(frame.withdrawing!=tactical.withdraw()) { nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); senses.cancel(npc.getUUID()); frame.following=false; frame.nextPath=now; }
        frame.withdrawing=tactical.withdraw();
        if(tactical.withdraw()) { if(instruction.command().intent().kind().equals(InitialWarDefinitions.CHARGE))cavalry.suspend(request.unitId());withdraw(npc,work,instruction,member,frame,tactical,
                arms.status()==WarFieldPorts.ChangeStatus.READY?profile:Optional.empty(),checkpoint.get(),now); return; }
        if (arms.status() != WarFieldPorts.ChangeStatus.READY) {
            var change = arms.status() == WarFieldPorts.ChangeStatus.PENDING ? nativeBindings.actors().equip(npc, request.id(), expectedKind, expectedKind) : new WarFieldPorts.WeaponChange(arms.status(), arms.reason());
            nativeBindings.actors().stopNavigation(npc); cancelDraw(npc,frame); frame.following=false; paths.cancel(npc.getUUID());
            report(npc,work,instruction,change.status()==WarFieldPorts.ChangeStatus.REVIEW?WarFieldReport.State.REVIEW:WarFieldReport.State.BLOCKED,change.reason(),now); return;
        }
        if(profile.isEmpty() && ranged.isEmpty()) { wait(npc,work,WarFieldReport.State.BLOCKED,"ORIGINAL_FOOT_WEAPON_CAPABILITY_REQUIRED",now); return; }
        if(instruction.command().intent().kind().equals(InitialWarDefinitions.CHARGE)){
            if(mobility.mode()==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.RIDING){cavalry.touch(instruction,now);cavalry(npc,work,instruction,member,frame,profile.orElseThrow(),checkpoint.get(),now);return;}
        }
        var center = point(station); var target = target(npc, frame, center);
        if (target.isEmpty()) { nativeBindings.actors().clearTarget(npc); frame.target = null; cancelDraw(npc, frame); }
        if (now >= frame.nextSense) {
            frame.nextSense = now + 20;
            senses.submit(npc.getUUID(), quota -> {
                if (!quota.tryConsume()) return WarWorkQueue.Step.complete(); var live = body(member);
                if (live.isPresent() && current(member, frame)) sense(live.get(), frame, center);
                return WarWorkQueue.Step.complete();
            }, now);
        }
        var desiredWeapon = WarWeaponChoice.choose(expectedKind, arms.backupHeld(), target.isPresent() ? OptionalDouble.of(npc.distanceToSqr(target.get())) : OptionalDouble.empty());
        if (desiredWeapon != (arms.backupHeld() ? WarAttackKind.SWORD_STRIKE : expectedKind)) {
            cancelDraw(npc, frame); nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false;
            var change = nativeBindings.actors().equip(npc, request.id(), expectedKind, desiredWeapon);
            report(npc, work, instruction, change.status() == WarFieldPorts.ChangeStatus.REVIEW ? WarFieldReport.State.REVIEW : WarFieldReport.State.BLOCKED, change.reason(), now); return;
        }
        if (ranged.isPresent() && target.isPresent()) {
            ranged(npc, work, instruction, frame, ranged.get(), checkpoint.get(), target.get(), now); return;
        }
        BlockPos destination = center;
        if (target.isPresent()) {
            var foe = target.get(); nativeBindings.actors().engage(npc, foe); destination = foe.blockPosition();
            boolean spearCharge=profile.get().kind()==WarAttackKind.SPEAR_JAB&&instruction.command().intent().kind().equals(InitialWarDefinitions.CHARGE);
            if(spearCharge)nativeBindings.actors().prepareCharge(npc,foe,instruction.command().operationId(),WarAttackKind.SPEAR_CHARGE);
            var decision = WarMeleePolicy.decide(profile.get(), npc.distanceToSqr(foe), foe.getBbWidth(), npc.getSensing().hasLineOfSight(foe), now, checkpoint.get().nextAttack());
            if (decision == WarMeleePolicy.Action.WAIT || decision == WarMeleePolicy.Action.STRIKE) {
                if (decision == WarMeleePolicy.Action.STRIKE) {
                    var reserved = checkpoint.get().reserveAttack(now, profile.get().recoveryTicks()).orElseThrow();
                    nativeBindings.checkpoints().store(npc, reserved);
                    var result = nativeBindings.actors().attack(npc, foe, spearCharge?WarAttackKind.SPEAR_CHARGE:profile.get().kind());
                    if (result.status() == WarFieldPorts.AttackStatus.UNSUPPORTED || result.status() == WarFieldPorts.AttackStatus.BLOCKED) {
                        report(npc, work, instruction, WarFieldReport.State.BLOCKED, result.reason(), now); return;
                    }
                }
                nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false;
                report(npc, work, instruction, WarFieldReport.State.ENGAGING, "NATIVE_SINGLE_MELEE_STRIKE", now); return;
            }
            if (decision == WarMeleePolicy.Action.HOLD_DISTANCE || decision == WarMeleePolicy.Action.BLOCKED) {
                nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false;
                report(npc, work, instruction, WarFieldReport.State.BLOCKED, "WEAPON_DISTANCE_OR_CLOCK_PREVENTS_ACTION", now); return;
            }
        }
        var nativeGoal = target.isPresent() ? position(target.get()) : station;
        nativeBindings.mobility().tick(npc);
        if (target.isEmpty() && nativeBindings.mobility().arrived(npc,nativeGoal)) {
            nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false;
            report(npc, work, instruction, WarFieldReport.State.HOLDING, "ACTUALLY_AT_ASSIGNED_STATION", now); return;
        }
        String movementProblem=navigate(npc,member,frame,nativeGoal,1.0,now);
        if(movementProblem!=null) { report(npc,work,instruction,WarFieldReport.State.BLOCKED,movementProblem,now); return; }
        report(npc, work, instruction, WarFieldReport.State.MOVING, target.isPresent() ? "BOUNDED_APPROACH" : "ACTUAL_NAVIGATION", now);
    }
    private void cavalry(NpcBase npc,WorkCommitment work,WarTransmission instruction,WarTransmission.Member member,Motion frame,WarWeaponProfile.Melee profile,WarBodyCheckpoint checkpoint,long now){
        var control=cavalry.control(instruction,member,now);cavalry.track(npc,instruction,member,now);
        if(control.status()!=com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Status.APPLIED){
            nativeBindings.actors().stopNavigation(npc);nativeBindings.actors().clearTarget(npc);paths.cancel(npc.getUUID());frame.following=false;
            report(npc,work,instruction,control.status()==com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Status.UNKNOWN?WarFieldReport.State.REVIEW:WarFieldReport.State.WAITING,control.reason(),now);return;
        }
        var state=control.state().orElseThrow();var destination=control.destination().orElseThrow();
        var front=point(cavalry.front(instruction,member));
        var permit=cavalry.take(instruction,member,now);
        if(permit.isPresent()){
            var hit=((ServerLevel)npc.level()).getEntity(permit.get().targetBody());
            if(hit instanceof NpcBase foe&&foe.getCharacterId().equals(permit.get().targetCharacter())&&permitted(npc,foe,front,8,6)
                    &&WarMeleePolicy.decide(profile,npc.distanceToSqr(foe),foe.getBbWidth(),npc.getSensing().hasLineOfSight(foe),now,checkpoint.nextAttack())==WarMeleePolicy.Action.STRIKE){
                nativeBindings.checkpoints().store(npc,checkpoint.reserveAttack(now,profile.recoveryTicks()).orElseThrow());
                var impact=nativeBindings.actors().attack(npc,foe,profile.kind()==WarAttackKind.SPEAR_JAB?WarAttackKind.MOUNTED_SPEAR_CHARGE:profile.kind());
                nativeBindings.actors().stopNavigation(npc);paths.cancel(npc.getUUID());frame.following=false;
                report(npc,work,instruction,WarFieldReport.State.ENGAGING,"CAVALRY_SINGLE_NATIVE_CONTACT:"+impact.status()+":"+impact.reason(),now);return;
            }
        }
        if(frame.cavalryWave!=state.wave()||frame.cavalryRun!=control.run()||!Objects.equals(frame.cavalryDestination,destination)){
            nativeBindings.actors().stopNavigation(npc);paths.cancel(npc.getUUID());frame.following=false;frame.nextPath=now;frame.cavalryWave=state.wave();frame.cavalryRun=control.run();frame.cavalryDestination=destination;
        }
        nativeBindings.mobility().tick(npc);
        if(!control.run()){
            nativeBindings.actors().cancelCharge(npc);
            nativeBindings.actors().clearTarget(npc);frame.target=null;senses.cancel(npc.getUUID());
            defendWhileWithdrawing(npc,Optional.of(profile),checkpoint,now);
            if(nativeBindings.mobility().arrived(npc,destination)){nativeBindings.actors().stopNavigation(npc);paths.cancel(npc.getUUID());frame.following=false;report(npc,work,instruction,WarFieldReport.State.REGROUPING,control.reason(),now);return;}
            String problem=navigate(npc,member,frame,destination,1.0,now);report(npc,work,instruction,problem==null?WarFieldReport.State.REGROUPING:WarFieldReport.State.BLOCKED,problem==null?control.reason():problem,now);return;
        }
        if(now>=frame.nextSense){frame.nextSense=now+10;senses.submit(npc.getUUID(),quota->{if(!quota.tryConsume())return WarWorkQueue.Step.complete();var body=body(member);if(body.isPresent()&&current(member,frame))sense(body.get(),frame,front);return WarWorkQueue.Step.complete();},now);}
        var target=target(npc,frame,front);var goal=destination;
        if(target.isPresent()){
            var foe=target.get();nativeBindings.actors().engage(npc,foe);goal=position(foe);
            if(profile.kind()==WarAttackKind.SPEAR_JAB)nativeBindings.actors().prepareCharge(npc,foe,instruction.command().operationId(),WarAttackKind.MOUNTED_SPEAR_CHARGE);
            var decision=WarMeleePolicy.decide(profile,npc.distanceToSqr(foe),foe.getBbWidth(),npc.getSensing().hasLineOfSight(foe),now,checkpoint.nextAttack());
            if(decision==WarMeleePolicy.Action.STRIKE){
                var result=cavalry.finish(instruction,member,cavalry.contact(npc,instruction,member,foe,now),now);
                if(result.status()==com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Status.UNAVAILABLE)cavalry.abort(instruction,member,now);
                if(profile.kind()!=WarAttackKind.SPEAR_JAB){nativeBindings.actors().stopNavigation(npc);paths.cancel(npc.getUUID());frame.following=false;}
                report(npc,work,instruction,WarFieldReport.State.WAITING,"CAVALRY_CONTACT_FENCE",now);return;
            }
        }else if(nativeBindings.mobility().arrived(npc,destination)){
            cavalry.finish(instruction,member,Optional.empty(),now);nativeBindings.actors().stopNavigation(npc);paths.cancel(npc.getUUID());frame.following=false;report(npc,work,instruction,WarFieldReport.State.REGROUPING,"CAVALRY_PASS_WITHOUT_CONTACT",now);return;
        }
        String problem=navigate(npc,member,frame,goal,1.25,now);
        if(problem!=null)cavalry.abort(instruction,member,now);
        report(npc,work,instruction,problem==null?WarFieldReport.State.MOVING:WarFieldReport.State.BLOCKED,problem==null?"CAVALRY_APPROACH":problem,now);
    }
    private void withdraw(NpcBase npc,WorkCommitment work,WarTransmission instruction,WarTransmission.Member member,Motion frame,WarCohesionRuntime.Control control,
                          Optional<WarWeaponProfile.Melee> melee,WarBodyCheckpoint checkpoint,long now) {
        cancelDraw(npc,frame); nativeBindings.actors().clearTarget(npc); frame.target=null; senses.cancel(npc.getUUID());
        defendWhileWithdrawing(npc,melee,checkpoint,now);
        var destination=control.destination().orElseThrow();
        nativeBindings.mobility().tick(npc);
        if(nativeBindings.mobility().arrived(npc,destination)) {
            nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following=false;
            report(npc,work,instruction,WarFieldReport.State.REGROUPING,"PHYSICALLY_AT_RALLY_NO_PURSUIT",now); return;
        }
        String problem=navigate(npc,member,frame,destination,control.forced()?1.2:1.0,now);
        if(frame.following && !frame.retreatRecorded) {
            NpcCombatRuntime.retreat(npc,UUID.nameUUIDFromBytes((member.contract()+":"+frame.order+":withdraw").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            frame.retreatRecorded=true;
        }
        report(npc,work,instruction,problem==null?WarFieldReport.State.RETREATING:WarFieldReport.State.BLOCKED,problem==null?control.reason():problem,now);
    }
    /** An actual recent attacker inside held-weapon reach may be struck; this never acquires a pursuit target or changes the retreat path. */
    private void defendWhileWithdrawing(NpcBase npc,Optional<WarWeaponProfile.Melee> melee,WarBodyCheckpoint checkpoint,long now) {
        if(melee.isEmpty() || !(npc.getLastHurtByMob() instanceof NpcBase attacker) || !attacker.isAlive() || !registered(attacker)
                || npc.isCombatAlly(attacker) || attacker.level()!=npc.level() || npc.getLastDamageGameTime()<0 || now<npc.getLastDamageGameTime() || now-npc.getLastDamageGameTime()>100) return;
        if(engagement.current(npc,attacker,now).isEmpty())return;
        var profile=melee.get();
        if(WarMeleePolicy.decide(profile,npc.distanceToSqr(attacker),attacker.getBbWidth(),npc.getSensing().hasLineOfSight(attacker),now,checkpoint.nextAttack())!=WarMeleePolicy.Action.STRIKE) return;
        nativeBindings.checkpoints().store(npc,checkpoint.reserveAttack(now,profile.recoveryTicks()).orElseThrow());
        nativeBindings.actors().attack(npc,attacker,profile.kind());
    }
    private String navigate(NpcBase npc,WarTransmission.Member member,Motion frame,WarIntent.Position nativeGoal,double speed,long now) {
        var destination=point(nativeGoal);
        if(!nativeGoal.equals(frame.movementGoal)) {
            nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following=false; frame.pathFailed=false; frame.nextPath=now; frame.movementGoal=nativeGoal;
        }
        boolean cautious = !nativeBindings.hazards().mayStand(npc, nativeGoal);
        if (cautious || !nativeBindings.actors().canStand(npc, nativeGoal)) {
            nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false;
            return cautious ? "OWN_REPORT_KNOWN_HAZARD" : "DESTINATION_UNLOADED_OR_NO_FOOTING";
        }
        if (frame.pathFailed && now < frame.nextPath) return "NATIVE_PATH_NOT_AVAILABLE";
        if (frame.following && frame.progress.tick(npc, destination)) {
            nativeBindings.actors().stopNavigation(npc); frame.following = false; frame.pathFailed = true;
            var order=militia.activePerson(member.character()).flatMap(v->dispatches.effective(v.request().unitId(),now));
            frame.nextPath = now + pathRetry(order);
            return "PATH_HAS_NO_ACTUAL_PROGRESS";
        }
        // Continue the original native foot path until it finishes or the progress check rejects it.
        if (frame.following && !npc.isPassenger() && !npc.getNavigation().isDone()) return null;
        if (now >= frame.nextPath) {
            frame.nextPath = now + 20; var goal = destination.immutable();
            boolean withdrawing=frame.withdrawing;
            paths.submit(npc.getUUID(), quota -> {
                if (!quota.tryConsume()) return WarWorkQueue.Step.complete(); var live = body(member);
                var instruction=militia.activePerson(member.character()).flatMap(v->dispatches.effective(v.request().unitId(),server.overworld().getGameTime()));
                if (live.isPresent() && current(member, frame) && frame.withdrawing==withdrawing && nativeGoal.equals(frame.movementGoal)
                        && instruction.filter(i->cohesion.control(i,member,server.overworld().getGameTime()).withdraw()==withdrawing).isPresent()) {
                    var actor = live.get(); boolean moving = nativeBindings.actors().navigate(actor, nativeGoal, speed);
                    if (!frame.following && moving) frame.progress.reset(actor, goal); frame.following = moving; frame.pathFailed = !moving;
                    if (!moving) frame.nextPath = server.overworld().getGameTime() + pathRetry(instruction);
                } return WarWorkQueue.Step.complete();
            }, now);
        }
        return null;
    }
    private int pathRetry(Optional<WarTransmission> instruction){
        int points=instruction.flatMap(leadership::assignment).filter(a->a.specialty()==WarLeadershipRecord.Specialty.MANEUVER)
                .map(WarLeadershipRuntime.Assignment::points).orElse(0);
        return WarLeadershipRecord.pathRetryTicks(points);
    }
    private void sense(NpcBase npc, Motion motion, BlockPos station) {
        if(motion.withdrawing) return;
        var candidates = ((ServerLevel)npc.level()).getEntitiesOfClass(NpcBase.class, npc.getBoundingBox().inflate(motion.engagementRange), other -> spatialContact(npc, other, station, motion.engagementRange, motion.stationRange));
        candidates.sort(Comparator.<NpcBase>comparingDouble(other -> npc.distanceToSqr(other)).thenComparing(NpcBase::getUUID));
        if(candidates.isEmpty()){motion.target=null;motion.senseOffset=0;return;}
        int start=Math.floorMod(motion.senseOffset,candidates.size()),count=Math.min(16,candidates.size());
        motion.senseOffset=(start+count)%candidates.size();
        for(int i=0;i<count;i++){var other=candidates.get((start+i)%candidates.size());if(engagement.contact(npc,other,server.overworld().getGameTime()).isPresent()){motion.target=other.getUUID();return;}}
        if(target(npc,motion,station).isEmpty())motion.target=null;
    }
    private Optional<NpcBase> target(NpcBase npc, Motion frame, BlockPos station) {
        if (frame.target == null) return Optional.empty(); var entity = ((ServerLevel)npc.level()).getEntity(frame.target);
        return entity instanceof NpcBase other && permitted(npc, other, station, frame.engagementRange, frame.stationRange) ? Optional.of(other) : Optional.empty();
    }
    private boolean permitted(NpcBase npc, NpcBase other, BlockPos station, double engagementRange, double stationRange) {
        return spatialContact(npc,other,station,engagementRange,stationRange)&&engagement.current(npc,other,server.overworld().getGameTime()).isPresent();
    }
    private boolean spatialContact(NpcBase npc,NpcBase other,BlockPos station,double engagementRange,double stationRange){
        var assigned=new WarIntent.Position(WarKey.parse(npc.level().dimension().identifier().toString()),station.getX(),station.getY(),station.getZ());
        if (npc == other || !other.isAlive() || !registered(other) || npc.isCombatAlly(other) || npc.distanceToSqr(other) > engagementRange * engagementRange || nativeBindings.mobility().pose(npc).distanceSquared(assigned) > 100
                || nativeBindings.mobility().pose(other).distanceSquared(assigned) > stationRange * stationRange || !npc.getSensing().hasLineOfSight(other)) return false;
        return true;
    }
    private void ranged(NpcBase npc, WorkCommitment work, WarTransmission instruction, Motion frame, WarWeaponProfile.Ranged profile,
                        WarBodyCheckpoint checkpoint, NpcBase foe, long now) {
        nativeBindings.actors().stopNavigation(npc); paths.cancel(npc.getUUID()); frame.following = false; nativeBindings.actors().engage(npc, foe);
        if (!Objects.equals(frame.drawingTarget, foe.getUUID()) || frame.drawingKind != profile.kind()) cancelDraw(npc, frame);
        if (frame.drawingSince >= 0 && !nativeBindings.actors().isRangedUsing(npc, profile.kind())) cancelDraw(npc, frame);
        if (now >= frame.nextAmmoCheck) { frame.hasAmmo = nativeBindings.actors().hasAmmunition(npc); frame.nextAmmoCheck = now + 20; }
        var action = WarRangedPolicy.decide(profile, npc.distanceToSqr(foe), npc.getSensing().hasLineOfSight(foe), frame.hasAmmo, now, checkpoint.nextAttack(), frame.drawingSince);
        switch (action) {
            case BEGIN -> { frame.drawingSince = now; frame.drawingTarget = foe.getUUID(); frame.drawingKind = profile.kind(); nativeBindings.actors().beginRangedUse(npc, profile.kind()); }
            case FIRE -> {
                var permission=engagement.current(npc,foe,now);
                if(permission.isEmpty()){cancelDraw(npc,frame);return;}
                var reserved = checkpoint.reserveAttack(now, profile.recoveryTicks()).orElseThrow(); nativeBindings.checkpoints().store(npc, reserved);
                var command = instruction.command();
                var permit = new WarShotPermit(work.intent().id(), npc.getCharacterId(), npc.getUUID(), command.armyId(), command.unitId(), command.operationId(), command.actorId(), permission.get().encounter(), foe.getCharacterId(), permission.get().immediateThreat(), now);
                var result = nativeBindings.actors().rangedAttack(npc, foe, profile.kind(), permit); cancelDraw(npc, frame); frame.nextAmmoCheck = now;
                report(npc, work, instruction, result.status() == WarFieldPorts.AttackStatus.LAUNCHED ? WarFieldReport.State.ENGAGING : WarFieldReport.State.BLOCKED, result.reason(), now); return;
            }
            case DRAW -> { }
            case COOLDOWN -> cancelDraw(npc, frame);
            default -> {
                cancelDraw(npc, frame);
                report(npc, work, instruction, WarFieldReport.State.BLOCKED, switch (action) {
                    case NO_AMMO -> "ACTUAL_AMMUNITION_REQUIRED"; case NEED_BACKUP -> "ACTUAL_BACKUP_SWORD_REQUIRED";
                    case OUT_OF_RANGE -> "RANGED_VISIBILITY_OR_RANGE_CHANGED"; default -> "RANGED_CLOCK_REQUIRES_REVIEW";
                }, now); return;
            }
        }
        report(npc, work, instruction, WarFieldReport.State.ENGAGING, action == WarRangedPolicy.Action.COOLDOWN ? "RANGED_RECOVERING" : "RANGED_DRAWING", now);
    }
    private void cancelDraw(NpcBase npc, Motion frame) {
        if (frame.drawingSince >= 0) nativeBindings.actors().cancelRangedUse(npc);
        frame.drawingSince = -1; frame.drawingTarget = null; frame.drawingKind = null;
    }
    /** Recheck each native spear ray contact. Preparation under an old order never grants a current strike. */
    public boolean mayMeleeImpact(NpcBase actor,NpcBase target,WarAttackKind kind,UUID preparedOrder){
        check();long now=server.overworld().getGameTime();var service=militia.activePerson(actor.getCharacterId());
        if(!active()||dispatches==null||service.isEmpty()||!registered(actor)||!registered(target)||actor.isNoAi()||actor.isSleeping())return false;
        var r=service.get().request();var member=new WarTransmission.Member(r.id(),r.characterId(),actor.getUUID());var motion=motions.get(actor.getUUID());var instruction=dispatches.effective(r.unitId(),now);
        if(farHolds(r.unitId())||motion==null||instruction.isEmpty()||!motion.order.equals(instruction.get().command().operationId())||dispatches.memberSlot(instruction.get(),member)<0||!core.servicePermits(r,now)||authority.check(r.actorId(),r.armyId())!=WarCommandService.Permission.ALLOWED)return false;
        if(kind!=WarAttackKind.SPEAR_JAB&&(!instruction.get().command().intent().kind().equals(InitialWarDefinitions.CHARGE)||!instruction.get().command().operationId().equals(preparedOrder)))return false;
        var h=sources.injuries().observeTriage(actor.getCharacterId());var needs=sources.life().needs(actor.getCharacterId());
        if(h.incapacitated()||h.mortalDanger()||actor.getHealth()<=actor.getMaxHealth()*.25F||needs==null||needs.hunger()>=900||!nativeBindings.mobility().observe(actor,r).usable()||actor.isPassenger()&&WarCampaignRuntime.existing(server).filter(c->c.horseReady(actor)).isEmpty())return false;
        var tactical=cohesion.control(instruction.get(),member,now);if(tactical.status()!=com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.APPLIED)return false;
        if(tactical.withdraw())return actor.getLastHurtByMob()==target&&actor.getLastDamageGameTime()>=0&&now-actor.getLastDamageGameTime()<=100&&engagement.current(actor,target,now).isPresent()&&!actor.isCombatAlly(target);
        var station=instruction.get().command().intent().kind().equals(InitialWarDefinitions.CHARGE)&&actor.isPassenger()?cavalry.front(instruction.get(),member):motion.station;
        return permitted(actor,target,point(station),motion.engagementRange,motion.stationRange);
    }
    public boolean mayImpact(WarShotPermit permit, NpcBase target) {
        check(); long now = server.overworld().getGameTime();
        if (!active() || dispatches == null || !permit.currentAt(now) || !registered(target)) return false;
        var service = militia.activePerson(permit.character()); if (service.isEmpty()) return false; var request = service.get().request();
        if (farHolds(request.unitId()) || !request.id().equals(permit.contract()) || !request.armyId().equals(permit.army()) || !request.unitId().equals(permit.unit())
                || !core.servicePermits(request,now) || authority.check(request.actorId(), permit.army()) != WarCommandService.Permission.ALLOWED
                || authority.check(permit.issuer(), permit.army()) != WarCommandService.Permission.ALLOWED) return false;
        var instruction = dispatches.find(permit.order());
        var member = new WarTransmission.Member(permit.contract(), permit.character(), permit.body());
        if (instruction.isEmpty() || instruction.get().received().isEmpty() || !instruction.get().members().contains(member)
                || !instruction.get().command().armyId().equals(permit.army()) || !instruction.get().command().unitId().equals(permit.unit())
                || !instruction.get().command().actorId().equals(permit.issuer())) return false;
        var shooter = body(member); if (shooter.isEmpty() || shooter.get().isCombatAlly(target) || shooter.get() == target) return false;
        var primary = WarAttackKind.primary(InitialWarDefinitions.LOADOUTS.find(request.loadout()).orElseThrow().primaryWeapon());
        var arms = nativeBindings.actors().arms(shooter.get(), request.id(), primary); var profile = arms.primaryRanged();
        if (arms.status() != WarFieldPorts.ChangeStatus.READY || profile.isEmpty()) return false;
        var station = instruction.get().station(member);
        if (!station.dimension().toString().equals(target.level().dimension().identifier().toString())
                || nativeBindings.mobility().pose(target).distanceSquared(station) > profile.get().maximumReach() * profile.get().maximumReach()) return false;
        var encounter = sources.encounters().encounter(permit.encounter());
        if(engagement.current(shooter.get(),target,now).filter(p->p.encounter().equals(permit.encounter())).isEmpty())return false;
        if (encounter == null || !encounter.activeAt(now) || sources.encounters().settling(encounter.id()) || !encounter.dimension().equals(target.level().dimension().identifier().toString())
                || target.blockPosition().distSqr(encounter.center()) > (double)encounter.radius() * encounter.radius()) return false;
        var action = CombatEncounterService.engagement(encounter, permit.character(), target.getCharacterId(), permit.immediateThreat() && permit.intendedTarget().equals(target.getCharacterId())).action();
        return action == EngagementAction.ATTACK || action == EngagementAction.DEFEND;
    }
    private boolean current(WarTransmission.Member member, Motion motion) {
        var service = militia.activePerson(member.character());
        return !closed && active() && !WarStaffRelayRuntime.existing(server).filter(r->r.occupies(member.character())).isPresent() && motions.get(member.body()) == motion && service.filter(record -> record.request().id().equals(member.contract())
                && !farHolds(record.request().unitId()) && core.servicePermits(record.request(),server.overworld().getGameTime()) && authority.check(record.request().actorId(), record.request().armyId()) == WarCommandService.Permission.ALLOWED).isPresent()
                && body(member).filter(n->!n.isPassenger()||WarCampaignRuntime.existing(server).filter(c->c.horseReady(n)).isPresent()).isPresent()
                && dispatches != null && dispatches.effective(service.orElseThrow().request().unitId(), server.overworld().getGameTime()).filter(value -> {
                    var tactical=cohesion.control(value,member,server.overworld().getGameTime());
                    // Returning to assembly must not depend on a sortie that cannot start before assembly.
                    // The current tactical observation still fences a changed withdrawal or regrouping state.
                    return value.command().operationId().equals(motion.order)
                            && tactical.status()==com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.APPLIED
                            && tactical.withdraw()==motion.withdrawing
                            && (tactical.withdraw()||!value.command().intent().kind().equals(InitialWarDefinitions.CHARGE)||currentCavalry(value,member,motion));
                }).isPresent();
    }
    private boolean currentCavalry(WarTransmission instruction,WarTransmission.Member member,Motion motion){
        var request=militia.activePerson(member.character()).orElseThrow().request();var body=body(member);
        if(body.isPresent()){var mode=nativeBindings.mobility().observe(body.get(),request).mode();if(mode==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.FOOT_FALLBACK||mode==com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mode.FOOT)return true;}
        var c=cavalry.control(instruction,member,server.overworld().getGameTime());return c.status()==com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.Status.APPLIED&&c.state().orElseThrow().wave()==motion.cavalryWave&&c.run()==motion.cavalryRun&&c.destination().filter(p->p.equals(motion.cavalryDestination)).isPresent();
    }
    private Optional<WarBodyCheckpoint> checkpoint(NpcBase npc, WarTransmission value, WarTransmission.Member member,
            int slot, long now, long recoveryTicks) {
        var saved = nativeBindings.checkpoints().read(npc); if (saved.status() == WarFieldPorts.ReadStatus.INVALID) return Optional.empty();
        var next = WarBodyCheckpoint.receive(saved.value(), value, member, slot, now, recoveryTicks,
                id -> WorkCommitmentRuntime.existing(server).flatMap(work -> work.find(id)).filter(old -> old.phase() == WorkCommitment.Phase.RELEASED).isPresent());
        if (next.isPresent() && !next.equals(saved.value())) nativeBindings.checkpoints().store(npc, next.orElseThrow());
        return next;
    }
    private Optional<NpcBase> body(WarTransmission.Member member) { return ItemLocation.personal(member.character(), member.body()).body(server, sources.characters()); }
    private Optional<LivingEntity> issuer(UUID character) {
        var record = sources.characters().byId(character);if(record==null||!record.alive())return Optional.empty();
        // Ordinary players are registered by account/current life, not in the NPC body table.
        var account=sources.characters().accountForCharacter(character);var currentPlayer=account==null?null:server.getPlayerList().getPlayer(account);
        if(currentPlayer!=null)return currentPlayer.isAlive()&&!currentPlayer.isRemoved()&&Objects.equals(CharacterRegistry.currentId(currentPlayer),character)?Optional.of(currentPlayer):Optional.empty();
        var binding = sources.characters().bodyOf(character); if (binding == null) return Optional.empty();
        var dimension = Identifier.tryParse(binding.dimensionId()); if (dimension == null) return Optional.empty();
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension)); var entity = level == null ? null : level.getEntity(binding.entityId());
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return Optional.empty();
        if (living instanceof NpcBase npc && !npc.getCharacterId().equals(character)
                || living instanceof Player && (!(living instanceof net.minecraft.server.level.ServerPlayer player) || !Objects.equals(CharacterRegistry.currentId(player),character))) return Optional.empty();
        return Optional.of(living);
    }
    private boolean registered(NpcBase npc) { return sources.characters().byId(npc.getCharacterId()) != null && sources.characters().byId(npc.getCharacterId()).alive()
            && Optional.ofNullable(sources.characters().bodyOf(npc.getCharacterId())).filter(binding -> binding.entityId().equals(npc.getUUID()) && binding.dimensionId().equals(npc.level().dimension().identifier().toString())).isPresent(); }
    private WarFieldPorts.Support fieldSupport(WarMilitiaRequest request) {
        var loadout = InitialWarDefinitions.LOADOUTS.find(request.loadout());
        if (loadout.isEmpty()) return new WarFieldPorts.Support(false, "UNKNOWN_LOADOUT");
        return nativeBindings.actors().support(WarAttackKind.primary(loadout.get().primaryWeapon()));
    }
    private void wait(NpcBase npc, WorkCommitment work, WarFieldReport.State state, String reason, long now) {
        stop(npc); var value = militia.service().flatMap(service -> service.find(work.intent().id())); if (value.isPresent()) report(npc, work, null, state, reason, now);
    }
    private void report(NpcBase npc, WorkCommitment work, WarTransmission instruction, WarFieldReport.State state, String reason, long now) {
        var record = militia.service().flatMap(service -> service.find(work.intent().id())).orElseThrow(); var old = reports.get(npc.getCharacterId());
        if (old == null || old.state() != state || !old.reason().equals(reason) || now - old.observedAt() >= 20) reports.put(npc.getCharacterId(), new WarFieldReport(work.intent().id(), npc.getCharacterId(), npc.getUUID(), record.request().unitId(),
                instruction == null ? Optional.empty() : Optional.of(instruction.command().operationId()), state, nativeBindings.mobility().pose(npc).block(), now, reason));
        var motion=motions.get(npc.getUUID());
        npc.reportWorkActivity(state.name(), motion!=null && motion.movementGoal!=null?point(motion.movementGoal):instruction == null ? null : point(instruction.station(new WarTransmission.Member(work.intent().id(), npc.getCharacterId(), npc.getUUID()))), reason);
    }
    @Override public void stop(NpcBase npc) { nativeBindings.actors().stopNavigation(npc); nativeBindings.actors().clearTarget(npc); nativeBindings.actors().cancelCharge(npc); nativeBindings.actors().cancelRangedUse(npc); nativeBindings.actors().settleEquipment(npc); motions.remove(npc.getUUID()); paths.cancel(npc.getUUID()); senses.cancel(npc.getUUID()); }
    private boolean active() { return !closed && core.session().filter(session -> session.phase() == com.jgeted.sagadyssey.social.war.runtime.WarSession.Phase.ACTIVE).isPresent(); }
    private static BlockPos point(WarIntent.Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private static WarIntent.Position position(LivingEntity npc) { return new WarIntent.Position(WarKey.parse(npc.level().dimension().identifier().toString()), npc.getBlockX(), npc.getBlockY(), npc.getBlockZ()); }
    private static WarDispatchService.Result rejected(WarDispatchService.Outcome outcome, String reason) { return new WarDispatchService.Result(outcome, Optional.empty(), reason); }
    private void check() { if (!server.isSameThread()) throw new IllegalStateException("field control requires server thread"); }
    @Override public void close() {
        check(); if (closed) return; closed = true; NpcPrimaryWorkRuntime.unregister(server, OWNER, this);
        for (var id : motions.keySet()) { paths.cancel(id); senses.cancel(id); }
        paths.close(); senses.close(); motions.clear(); reports.clear(); dispatchReady.clear();if(far!=null)far.close();if(cavalry!=null)cavalry.close(); if(cohesion!=null) cohesion.close(); leadership.close(); if (journal != null) journal.close();
    }
}
