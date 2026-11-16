package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.faction.StandingModifier;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.application.WarBattlePolicy;
import com.jgeted.sagadyssey.social.war.application.WarBattlePolicy.Source;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarBattlePorts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import java.util.function.*;

/** Ordinary-world host. All combat effects and final settlement remain in the original shared system. */
final class WarBattleRuntime implements Engagement<NpcBase>{
    static final String KIND="sagadyssey:war_force";
    private static final double CONTACT_RADIUS_SQUARED=32*32;
    private final MinecraftServer server;
    private final WarFieldRuntime.Sources sources;
    private final BiFunction<NpcBase,Long,Optional<WarMilitiaRequest>> service;
    private final Function<UUID,Optional<WarMilitiaRequest>> membership;
    private final Function<UUID,List<WarMobilization>> roster;
    private final Map<UUID, List<NpcBase>> nearbyVictims = new HashMap<>();
    private long witnessTick = -1;
    private record Evidence(Source source,boolean immediate,NpcBase victim){}

    WarBattleRuntime(MinecraftServer server,WarFieldRuntime.Sources sources,
                    BiFunction<NpcBase,Long,Optional<WarMilitiaRequest>> service,Function<UUID,Optional<WarMilitiaRequest>> membership,Function<UUID,List<WarMobilization>> roster){
        this.server=Objects.requireNonNull(server);this.sources=Objects.requireNonNull(sources);
        this.service=Objects.requireNonNull(service);this.membership=Objects.requireNonNull(membership);this.roster=Objects.requireNonNull(roster);
    }
    public Optional<Permit> current(NpcBase actor,NpcBase target,long now){
        var request=service.apply(actor,now);if(request.isEmpty()||!valid(actor,target)||friendly(actor,target,request.get(),now))return Optional.empty();
        var encounters = sources.encounters().activeEncounters(actor.getCharacterId(),now);
        if (encounters.stream().noneMatch(encounter -> encounter.sideOf(target.getCharacterId()) != null)) return Optional.empty();
        var evidence=evidence(actor,target,request.get(),now);
        for(var encounter:encounters){
            if(!WarExistingEngagement.allows(sources.encounters(),encounter,actor,target,evidence.immediate(),now))continue;
            boolean hosted=sources.encounters().bindings(encounter.id()).stream().anyMatch(b->KIND.equals(b.ownerKind()));
            var source=WarBattlePolicy.source(new WarBattlePolicy.Facts(true,true,false,evidence.source()==Source.HOSTILITY,
                    evidence.source()==Source.SELF_DEFENSE,evidence.source()==Source.GROUP_DEFENSE,true,hosted));
            if(source!=Source.NONE)return Optional.of(new Permit(encounter.id(),evidence.immediate()));
        }return Optional.empty();
    }
    public Optional<Permit> contact(NpcBase actor,NpcBase target,long now){
        check();var existing=current(actor,target,now);
        if(existing.isPresent()){
            var request=service.apply(actor,now).orElseThrow();
            retainServingHost(actor,request,existing.get(),now);
            return existing;
        }
        var request=service.apply(actor,now);if(request.isEmpty()||!valid(actor,target)||friendly(actor,target,request.get(),now))return Optional.empty();
        var evidence=evidence(actor,target,request.get(),now);if(evidence.source()==Source.NONE)return Optional.empty();
        var data=sources.encounters();var own=data.activeEncounters(actor.getCharacterId(),now);
        // Never move a person between live encounters, end someone else's episode, or upgrade an existing rule.
        if(!own.isEmpty()){
            if(own.size()!=1)return Optional.empty();var e=own.getFirst();
            var binding=data.binding(e.id(),KIND,request.get().armyId());
            if(binding==null||!binding.principal().equals(request.get().actorId())||e.sides().size()!=2||e.sideOf(target.getCharacterId())!=null||data.settling(e.id()))return Optional.empty();
            var opposing=e.sides().stream().filter(s->!s.id().equals(binding.side())).findFirst().orElseThrow();
            if(!inside(e,target)||!CombatEncounterService.enlist(data,e,opposing.id(),Set.of(target.getCharacterId()),now))return Optional.empty();
            return current(actor,target,now);
        }
        // A real attack may already have opened an individual self-defense episode before the next sense slot.
        if(evidence.victim()!=null){
            var episodes=data.activeEncounters(evidence.victim().getCharacterId(),now);
            if(episodes.size()==1){var e=episodes.getFirst();var side=e.sideOf(evidence.victim().getCharacterId());var opposing=e.sideOf(target.getCharacterId());
                if(side!=null&&opposing!=null&&!side.id().equals(opposing.id())&&inside(e,actor)&&inside(e,target)){
                    var binding=new CombatEncounterBinding(KIND,request.get().armyId(),request.get().actorId(),e.id(),side.id());
                    if(CombatEncounterService.enlist(data,e,binding,members(actor,target,request.get(),e,now),now))return current(actor,target,now);
                }
            }
        }
        var targetEpisodes=data.activeEncounters(target.getCharacterId(),now);
        if(targetEpisodes.size()==1){
            var e=targetEpisodes.getFirst();var enemy=e.sideOf(target.getCharacterId());
            if(enemy!=null&&inside(e,actor)){
                var binding=data.binding(e.id(),KIND,request.get().armyId());
                if(binding==null&&evidence.source()==Source.HOSTILITY)binding=alliedBinding(actor,request.get(),e,enemy,now).orElse(null);
                if(binding!=null&&binding.principal().equals(request.get().actorId())&&!enemy.id().equals(binding.side())
                        &&CombatEncounterService.enlist(data,e,binding,members(actor,target,request.get(),e,now),now))return current(actor,target,now);
            }
        }
        if(!targetEpisodes.isEmpty())return Optional.empty();
        if(!data.hostCapacityAvailable())return Optional.empty();
        var id=UUID.randomUUID();var side=UUID.randomUUID();var rule=evidence.source()==Source.HOSTILITY?EngagementRule.LETHAL_FORCE:EngagementRule.SELF_DEFENSE_ONLY;
        var context=com.jgeted.sagadyssey.social.work.NpcWorkContext.from(actor);
        CombatEncounterService.startHosted(data,id,context.workplaceSettlementId(),actor.level().dimension().identifier().toString(),actor.blockPosition(),32,
                List.of(new CombatSide(side,"已同意军役的在场成员",null,members(actor,target,request.get(),null,now),rule),
                        new CombatSide(UUID.randomUUID(),"当前已核验的交战对象",null,Set.of(target.getCharacterId()),rule)),
                new CombatEncounterBinding(KIND,request.get().armyId(),request.get().actorId(),id,side),now);
        return current(actor,target,now);
    }

    /** A verified participant can report an existing lethal encounter under its own original command authority. */
    private void retainServingHost(NpcBase actor,WarMilitiaRequest request,Permit permit,long now){
        var data=sources.encounters();
        if(data.binding(permit.encounter(),KIND,request.armyId())!=null)return;
        var episode=data.encounter(permit.encounter());
        if(episode==null||episode.sides().size()!=2||data.bindings(episode.id()).stream().noneMatch(b->KIND.equals(b.ownerKind())))return;
        var side=episode.sideOf(actor.getCharacterId());
        if(side==null||side.rule()!=EngagementRule.LETHAL_FORCE)return;
        var binding=new CombatEncounterBinding(KIND,request.armyId(),request.actorId(),episode.id(),side.id());
        CombatEncounterService.enlist(data,episode,binding,Set.of(actor.getCharacterId()),now);
    }

    /** A second army keeps its own owner and can join only the side of a witnessed ally. */
    private Optional<CombatEncounterBinding> alliedBinding(NpcBase actor,WarMilitiaRequest request,CombatEncounter encounter,CombatSide enemy,long now){
        if(encounter.sides().size()!=2||sources.encounters().settling(encounter.id())
                ||sources.encounters().bindings(encounter.id()).stream().noneMatch(b->KIND.equals(b.ownerKind())))return Optional.empty();
        var side=encounter.sides().stream().filter(s->!s.id().equals(enemy.id())).findFirst().orElseThrow();
        if(side.rule()!=EngagementRule.LETHAL_FORCE)return Optional.empty();
        boolean joined=side.members().stream().map(person->body(actor,person)).filter(Objects::nonNull).filter(witness->inside(encounter,witness))
                .anyMatch(witness->service.apply(witness,now).filter(previous->sameForce(request,previous)||allied(actor,request,witness,previous)).isPresent());
        return joined?Optional.of(new CombatEncounterBinding(KIND,request.armyId(),request.actorId(),encounter.id(),side.id())):Optional.empty();
    }
    private boolean allied(NpcBase actor,WarMilitiaRequest request,NpcBase witness,WarMilitiaRequest witnessRequest){
        var a=commandFaction(request).orElseGet(()->bodyFaction(actor));
        var b=commandFaction(witnessRequest).orElseGet(()->bodyFaction(witness));
        if(a.isEmpty()||b.isEmpty()||a.equals("sagadyssey:player")||b.equals("sagadyssey:player"))return false;
        var politics=PoliticalStateData.get(server);
        return politics.relation(a,b)==PoliticalRelation.ALLIED&&politics.relation(b,a)==PoliticalRelation.ALLIED;
    }
    private Set<UUID> members(NpcBase actor,NpcBase target,WarMilitiaRequest request,CombatEncounter encounter,long now){
        var result=new HashSet<UUID>();result.add(actor.getCharacterId());
        for(var value:roster.apply(request.unitId())){
            var other=body(actor,value.request().characterId());if(other==null||other==target||other.distanceToSqr(actor)>CONTACT_RADIUS_SQUARED)continue;
            var current=service.apply(other,now);if(current.isEmpty()||!sameForce(request,current.get())||friendly(other,target,current.get(),now))continue;
            if(encounter!=null&&!inside(encounter,other))continue;
            if(sources.encounters().activeEncounters(other.getCharacterId(),now).stream().anyMatch(e->encounter==null||!e.id().equals(encounter.id())))continue;
            result.add(other.getCharacterId());
        }return Set.copyOf(result);
    }
    private Evidence evidence(NpcBase actor,NpcBase target,WarMilitiaRequest request,long now){
        boolean own=WarExistingEngagement.recentAssault(actor,target,now);
        boolean hostile=declaredRelation(actor,request,target,now).map(relation->relation==PoliticalRelation.WAR)
                .orElseGet(()->StandingModifier.isHostileBetween(actor,target,actor.getOwnerUUID(),target.getOwnerUUID()));
        if(own)return new Evidence(hostile?Source.HOSTILITY:Source.SELF_DEFENSE,true,actor);
        int checked = 0;
        for(var victim:recentVictims(actor,now)){
            if (!victim.isAlive() || victim.distanceToSqr(actor)>CONTACT_RADIUS_SQUARED || !WarExistingEngagement.recentAssault(victim,target,now)) continue;
            if (checked++ == 128) break;
            var current=service.apply(victim,now);if(current.isPresent()&&sameForce(request,current.get()))return new Evidence(hostile?Source.HOSTILITY:Source.GROUP_DEFENSE,true,victim);
        }
        var account=sources.characters().accountForCharacter(request.actorId());var principal=account==null?null:server.getPlayerList().getPlayer(account);
        if(principal!=null&&principal.isAlive()&&principal.level()==actor.level()&&principal.distanceToSqr(actor)<=CONTACT_RADIUS_SQUARED
                &&principal.getLastHurtByMob()==target&&principal.tickCount>=principal.getLastHurtByMobTimestamp()&&principal.tickCount-principal.getLastHurtByMobTimestamp()<=100)
            return new Evidence(hostile?Source.HOSTILITY:Source.GROUP_DEFENSE,true,null);
        return new Evidence(hostile?Source.HOSTILITY:Source.NONE,false,null);
    }
    private List<NpcBase> recentVictims(NpcBase actor,long now) {
        if (now != witnessTick) {
            nearbyVictims.clear();
            witnessTick = now;
        }
        return nearbyVictims.computeIfAbsent(actor.getUUID(), ignored -> {
            var victims = ((ServerLevel)actor.level()).getEntitiesOfClass(NpcBase.class,actor.getBoundingBox().inflate(32),
                    n -> n.isAlive() && n.getLastHurtByMob() instanceof NpcBase attacker && WarExistingEngagement.recentAssault(n,attacker,now));
            victims.sort(Comparator.<NpcBase>comparingDouble(actor::distanceToSqr).thenComparing(NpcBase::getUUID));
            return victims;
        });
    }
    private boolean friendly(NpcBase actor,NpcBase target,WarMilitiaRequest request,long now){
        if(actor.isCombatAlly(target)||target.isCombatAlly(actor))return true;
        // A reserve with no received order is still friendly; execution qualification is a separate gate.
        var other=membership.apply(target.getCharacterId());if(other.isPresent()&&(sameForce(request,other.get())||request.actorId().equals(other.get().actorId())))return true;
        var relation=declaredRelation(actor,request,target,now);
        if(relation.isEmpty()){
            String from=bodyFaction(actor),to=bodyFaction(target);
            if(!from.isEmpty()&&!to.isEmpty()){
                var politics=PoliticalStateData.get(server);
                if(politics.hasRelation(from,to))relation=Optional.of(politics.relation(from,to));
            }
        }
        return relation.map(value->switch(value){
            case ALLIED,FRIENDLY,VASSAL,OVERLORD->true;
            default->false;
        }).orElse(false);
    }
    /**
     * A recruited body may become part of the common player faction on reload.
     * A currently permitted soldier fights under the recognized character who holds that service.
     */
    private Optional<PoliticalRelation> declaredRelation(NpcBase actor,WarMilitiaRequest request,NpcBase target,long now){
        var a=commandFaction(request);
        var b=membership.apply(target.getCharacterId())
                .filter(service->WarServerEvents.existing(server).filter(core->core.servicePermits(service,now)).isPresent())
                .flatMap(this::commandFaction);
        if(a.isEmpty()&&b.isEmpty())return Optional.empty();
        String from=a.orElseGet(()->bodyFaction(actor)),to=b.orElseGet(()->bodyFaction(target));
        if(from.isEmpty()||to.isEmpty()||from.equals("sagadyssey:player")||to.equals("sagadyssey:player"))return Optional.empty();
        return Optional.of(PoliticalStateData.get(server).relation(from,to));
    }
    private Optional<String> commandFaction(WarMilitiaRequest request){
        var owner=sources.characters().byId(request.actorId());
        if(owner==null||!owner.alive())return Optional.empty();
        String faction=owner.factionId();
        return faction.equals("sagadyssey:player")||faction.equals("sagadyssey:wilderness")||FactionRegistry.get(faction)==null
                ?Optional.empty():Optional.of(faction);
    }
    private static String bodyFaction(NpcBase npc){return npc.getFaction()==null?"":npc.getFaction().id();}
    private boolean valid(NpcBase actor,NpcBase target){
        return actor!=target&&actor.level()==target.level()&&actor.isAlive()&&target.isAlive()&&registered(actor)&&registered(target);
    }
    private boolean registered(NpcBase npc){var person=sources.characters().byId(npc.getCharacterId());var body=sources.characters().bodyOf(npc.getCharacterId());return person!=null&&person.alive()&&body!=null&&body.entityId().equals(npc.getUUID());}
    private NpcBase body(NpcBase observer,UUID person){var binding=sources.characters().bodyOf(person);if(binding==null)return null;var entity=((ServerLevel)observer.level()).getEntity(binding.entityId());return entity instanceof NpcBase npc&&npc.getCharacterId().equals(person)&&registered(npc)?npc:null;}
    private static boolean sameForce(WarMilitiaRequest a,WarMilitiaRequest b){return a.armyId().equals(b.armyId())&&a.actorId().equals(b.actorId());}
    private static boolean inside(CombatEncounter e,NpcBase npc){return e.dimension().equals(npc.level().dimension().identifier().toString())&&npc.blockPosition().distSqr(e.center())<=(double)e.radius()*e.radius();}
    private void check(){if(!server.isSameThread())throw new IllegalStateException("battle contact requires server thread");}
}
