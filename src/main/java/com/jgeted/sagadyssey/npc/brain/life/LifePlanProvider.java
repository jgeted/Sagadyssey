package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import java.util.*;

/** Only own records, explicitly known places and currently perceived nearby partners. */
public final class LifePlanProvider {
    private LifePlanProvider() { }
    public static boolean eligible(NpcBase npc) {
        return npc.getCommand()==NpcCommand.WORK && npc.getTarget()==null && npc.isAlive()
                && NpcBrainRuntime.isPlanned(npc) && NpcLifeData.get(MinecraftApi.server(npc)).record(npc.getCharacterId())!=null;
    }
    public static Optional<NpcPlan> choose(NpcBase npc,long now,boolean idle) {
        return choose(npc,now,idle,false);
    }
    /** Survival is not gated by an ordinary FOLLOW/STAY/PATROL field; no social invitation bypass. */
    public static Optional<NpcPlan> chooseUrgent(NpcBase npc,long now) {
        var needs=NpcLifeData.get(MinecraftApi.server(npc)).needs(npc.getCharacterId());
        return needs!=null&&needs.hunger()>=800?choose(npc,now,false,true):Optional.empty();
    }
    private static Optional<NpcPlan> choose(NpcBase npc,long now,boolean idle,boolean urgent) {
        if((!urgent&&!eligible(npc) || urgent&&(!npc.isAlive()||npc.getTarget()!=null||!NpcBrainRuntime.isPlanned(npc)))
                || !npc.onGround() && !npc.isSleeping())return Optional.empty();
        var data=LifeActivityData.get(MinecraftApi.server(npc)); if(now<data.retryAt(npc.getUUID()))return Optional.empty();
        var level=(ServerLevel)npc.level(); var life=NpcLifeData.get(MinecraftApi.server(npc));var record=life.record(npc.getCharacterId());
        String dimension=level.dimension().identifier().toString();if(record==null||!dimension.equals(record.dimension()))return Optional.empty();
        var needs=life.ensureNeeds(npc.getCharacterId(),now);
        if(needs.hunger()>=350) {
            var shared=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
            if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc)!=null&&shared!=null&&dimension.equals(shared.dimension())
                    &&hasPlannedFood(npc,shared.sharedStorage(),"HOUSEHOLD",true))
                return plan(npc,"EAT",shared.sharedStorage(),40,now,Map.of("source","HOUSEHOLD"));
            if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodSlot(npc,npc.getPersonalInventory(),"BAG",npc.blockPosition(),(needs.hunger()>=800||com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.ordinaryRawDiet(npc)),now)>=0)
                return plan(npc,"EAT",npc.blockPosition(),40,now,Map.of("source","BAG"));
            if(hasPlannedFood(npc,record.privateStoragePosition(),"PRIVATE",needs.hunger()>=800||com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.ordinaryRawDiet(npc)))return plan(npc,"EAT",record.privateStoragePosition(),40,now,Map.of("source","PRIVATE"));
            var home=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
            if(home!=null && dimension.equals(home.dimension()) && hasPlannedFood(npc,home.sharedStorage(),"HOUSEHOLD",needs.hunger()>=800||com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.ordinaryRawDiet(npc)))
                return plan(npc,"EAT",home.sharedStorage(),40,now,Map.of("source","HOUSEHOLD"));
        }
        if(urgent)return Optional.empty();
        if(NpcLifeBehavior.isRestWindow(record.shift(),level.getDayTime()) && knownLoaded(npc,record.bedPosition())) {
            var bed=NpcResidenceFixtures.normalizeBedHead(level,record.bedPosition());
            if(level.getBlockState(bed).is(BlockTags.BEDS))return plan(npc,"SLEEP",bed,6000,now,Map.of());
        }
        if(!idle)return Optional.empty();
        if(record.bedPosition()==null && record.privateStoragePosition()==null && record.relations().isEmpty()
                && data.knownPlaces(npc.getUUID()).isEmpty() && HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId())==null)
            return Optional.empty(); // Existing unassigned workers retain their finite professional rest.
        var household=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
        // Perception is bounded to 16 blocks and line of sight, never all loaded/global residents.
        var peers=level.getEntitiesOfClass(NpcBase.class,npc.getBoundingBox().inflate(16),other->other!=npc && other.isAlive() && npc.hasLineOfSight(other));
        if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc)!=null)
            peers.sort(Comparator.<NpcBase>comparingInt(other->{var need=life.needs(other.getCharacterId());return need==null?0:-need.hunger();}).thenComparing(NpcBase::getCharacterId));
        if(household!=null && com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodSlot(npc,npc.getPersonalInventory(),"BAG",npc.blockPosition(),true,now)>=0) {
            for(var other:peers) {
                var otherNeeds=life.needs(other.getCharacterId());
                if(household.contains(other.getCharacterId()) && otherNeeds!=null && otherNeeds.hunger()>=350)
                    return plan(npc,"CARE",other.blockPosition(),40,now,Map.of("peer",other.getUUID().toString(),"source","BAG"));
            }
        }
        for(var other:peers) {
            if(!eligible(other) || now<data.retryAt(other.getUUID()))continue;
            var peerPlan=NpcBrainRuntime.data(MinecraftApi.server(npc)).active(other.getUUID()).orElse(null);
            if(peerPlan!=null && !peerPlan.currentStep().executorId().equals(com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkPlanFactory.REST)
                    && !(peerPlan.providerId().equals(LifePlanFactory.PROVIDER)&&peerPlan.currentStep().params().getOrDefault("operation","").equals("WAIT")))continue;
            var peerNeeds=life.needs(other.getCharacterId()); if(peerNeeds!=null&&peerNeeds.hunger()>=350)continue;
            if(npc.getUUID().compareTo(other.getUUID())>=0)continue;
            // A stranger can be seen, but choosing a social encounter needs a known relationship.
            boolean known=record.relations().stream().anyMatch(r->r.otherCharacterId().equals(other.getCharacterId())&&r.strength()>=0);
            if(!known || !willingToTalk(other,npc))continue;
            var host=life.record(other.getCharacterId());
            if(record.bedPosition()!=null && npc.blockPosition().distSqr(record.bedPosition())<=9)host=record;
            boolean visit=host!=null && host.bedPosition()!=null && (host.npcId().equals(npc.getCharacterId())?npc:other).blockPosition().distSqr(host.bedPosition())<=9;
            String operation=visit?"VISIT":"TALK";
            var definition=VisitActivityRegistry.definitions().get(net.minecraft.resources.Identifier.parse(visit?"sagadyssey:visit":"sagadyssey:talk"));
            if(definition==null || !definition.availableAt(level.getDayTime()))continue;
            BlockPos place=visit?host.bedPosition():npc.blockPosition(); UUID id=UUID.randomUUID();
            var params=Map.of("session",id.toString(),"capacity","2","peer",other.getUUID().toString());
            var first=LifePlanFactory.create(npc.getUUID(),operation,dimension,place,definition.durationTicks(),now,params);
            var second=LifePlanFactory.create(other.getUUID(),operation,dimension,place,definition.durationTicks(),now,
                    Map.of("session",id.toString(),"capacity","2","peer",npc.getUUID().toString()));
            data.invite(ConversationSession.invite(id,npc.getUUID(),other.getUUID(),dimension,place,definition.durationTicks(),now,now+1200));
            if(peerPlan!=null)NpcBrainRuntime.cancel(other,com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason.MANUAL,"接受已知熟人的当面交谈邀请，结束短等待");
            NpcBrainRuntime.installLifePlan(other,second);return Optional.of(first);
        }
        for(var place:data.knownPlaces(npc.getUUID())) {
            if(!dimension.equals(place.dimension()) || !knownLoaded(npc,place.position()))continue;
            var definition=VisitActivityRegistry.definitions().get(net.minecraft.resources.Identifier.parse(place.activity()));
            if(definition==null || !definition.availableAt(level.getDayTime()) || definition.kind()==VisitActivity.Kind.TALK || definition.kind()==VisitActivity.Kind.VISIT)continue;
            var state=level.getBlockState(place.position());
            if(definition.kind()==VisitActivity.Kind.READ && (!state.is(net.minecraft.world.level.block.Blocks.LECTERN)
                    || !state.getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK)))continue;
            if(definition.kind()==VisitActivity.Kind.TAVERN && !state.is(net.minecraft.tags.BlockTags.CAMPFIRES))continue;
            return plan(npc,definition.kind().name(),place.position(),definition.durationTicks(),now,Map.of("capacity",Integer.toString(definition.capacity())));
        }
        var homeStand=com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.idleHomeStand(npc);
        if(homeStand!=null)return plan(npc,"OBSERVE",homeStand,100,now,Map.of());
        if(knownLoaded(npc,record.bedPosition()) && npc.blockPosition().distSqr(record.bedPosition())>9)
            return plan(npc,"HOME",record.bedPosition(),100,now,Map.of());
        return plan(npc,"WAIT",npc.blockPosition(),100,now,Map.of());
    }
    private static boolean willingToTalk(NpcBase recipient,NpcBase initiator) {
        var own=NpcLifeData.get(MinecraftApi.server(recipient)).record(recipient.getCharacterId());
        return own!=null && !recipient.isSleeping() && recipient.hasLineOfSight(initiator)
                && own.relations().stream().anyMatch(r->r.otherCharacterId().equals(initiator.getCharacterId())&&r.strength()>=0);
    }
    /** A known processing/input store may be empty or contain only unusable raw food. */
    private static boolean hasPlannedFood(NpcBase npc,BlockPos target,String kind,boolean emergency) {
        if(!knownLoaded(npc,target))return false;
        var inventory=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(npc.level().getBlockEntity(target));
        if(inventory==null || com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodSlot(npc,inventory,kind,target,emergency,npc.level().getGameTime())<0)return false;
        // A stocked but locally unreachable pantry must not hide an existing legal alternative.
        // Distant routes retain the original segmented movement and failure handling.
        if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc)!=null
                &&npc.blockPosition().distSqr(target)<=12D*12D
                &&!FieldworkRoutes.closeEnough(npc,target)&&FieldworkRoutes.plan(npc,target,false)==null) {
            LifeActivityData.get(MinecraftApi.server(npc)).deferPlace(npc.getUUID(),npc.level().dimension().identifier().toString(),target,npc.level().getGameTime());
            return false;
        }
        return true;
    }
    private static boolean knownLoaded(NpcBase npc,BlockPos target) { return target!=null && npc.level().hasChunkAt(target) && !com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.protects(MinecraftApi.server(npc),npc.level().dimension().identifier().toString(),target)
            && npc.level().getGameTime()>=LifeActivityData.get(MinecraftApi.server(npc)).placeRetryAt(npc.getUUID(),npc.level().dimension().identifier().toString(),target); }
    private static Optional<NpcPlan> plan(NpcBase npc,String op,BlockPos target,int duration,long now,Map<String,String> extra) {
        return Optional.of(LifePlanFactory.create(npc.getUUID(),op,npc.level().dimension().identifier().toString(),target,duration,now,extra));
    }
}
