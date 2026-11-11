package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import java.util.*;

/** Read-only purpose limits for the versioned natural farm. These are not ownership grants. */
public final class NaturalFarmRules {
    public static final int SEEDS=12, HOUSEHOLD_FLOOR=6, PAYMENT=2;
    private NaturalFarmRules() {}

    public static NaturalVillageBuilding forResident(NpcBase npc){
        if(MinecraftApi.server(npc)==null)return null;
        var development=com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.residentVillage(MinecraftApi.server(npc),npc.getCharacterId());
        if(development!=null)return development;
        var data=NaturalVillageLifeData.existing(MinecraftApi.server(npc));if(data==null)return null;
        var home=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());if(home==null)return null;
        for(var v:data.villages())if(v.getBooleanOr("Ready", false)){
            var b=NaturalVillageLifeData.decode(v);
            if(!NaturalVillageLayout.autonomousFarm(b)||!NaturalVillageLifeData.home(b).equals(home.id()))continue;
            for(int i=0;i<3;i++)if(NaturalVillageLifeData.person(b,i).equals(npc.getCharacterId()))return b;
        }
        return null;
    }
    public static boolean isFarmer(NpcBase npc){
        if(npc.getProfession()!=NpcProfession.FARMER)return false;
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.farmer(npc))return true;
        var b=forResident(npc);return b!=null&&NaturalVillageLifeData.person(b,0).equals(npc.getCharacterId());
    }
    public static boolean severelyHungry(MinecraftServer server,NaturalVillageBuilding b){
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.supports(b))
            return HouseholdData.get(server).households().stream().filter(h->Objects.equals(h.settlementId(),b.settlementId())).anyMatch(h->severelyHungry(server,h));
        var home=HouseholdData.get(server).byId(NaturalVillageLifeData.home(b));if(home==null)return false;
        return severelyHungry(server,home);
    }
    private static boolean severelyHungry(MinecraftServer server,com.jgeted.sagadyssey.social.household.HouseholdRecord home){
        var life=NpcLifeData.get(server);var characters=CharacterRegistryData.get(server);
        return home.members().stream().anyMatch(m->{var person=characters.byId(m.characterId());var need=life.needs(m.characterId());return person!=null&&person.alive()&&need!=null&&need.hunger()>=800;});
    }
    public static boolean paymentAllowed(int available,boolean severe){return !severe&&available>=HOUSEHOLD_FLOOR+PAYMENT;}

    /** Overlap must have been proved for this container, owner, item and planting intent. */
    public static int purposeAvailable(int owned,int protection,int samePurposeReserved,int otherReserved){
        if(owned<0||protection<0||samePurposeReserved<0||otherReserved<0)throw new IllegalArgumentException("negative inventory quantity");
        return (int)Math.max(0L,(long)owned-Math.max(protection,samePurposeReserved)-otherReserved);
    }
    public static boolean foodEmergency(NpcBase npc){
        var b=forResident(npc);if(b==null)return false;
        var home=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
        if(home==null||!severelyHungry(MinecraftApi.server(npc),home))return false;
        var at=home.sharedStorage();if(at==null||!home.dimension().equals(npc.level().dimension().identifier().toString())||!npc.level().hasChunkAt(at))return true;
        var block=npc.level().getBlockEntity(at);
        if(!(block instanceof Container box))return true;
        var id=ContainerIds.block("household_storage",home.dimension(),at);
        return usable(npc,box,id,SubjectRef.household(home.id()),new ItemFingerprint("minecraft:carrot",0),0,npc.level().getGameTime())<1;
    }
    public static int seedProtection(NpcBase npc){return isFarmer(npc)?foodEmergency(npc)?1:SEEDS:16;}

    /** Ordinary meals and care never release the seed reserve merely because a peer is hungry. */
    public static int eatingProtection(NpcBase npc){
        if(!isFarmer(npc))return 0;
        var needs=NpcLifeData.get(MinecraftApi.server(npc)).needs(npc.getCharacterId());
        if(needs==null||needs.hunger()<800||!foodEmergency(npc))return SEEDS;
        var work=FieldworkData.get(MinecraftApi.server(npc));
        boolean newHarvest=work.receiptsFor(npc.getUUID()).stream().anyMatch(r->r.action().operation()==FieldworkAction.Operation.HARVEST&&r.committedAt()>needs.lastAteAt()
                &&r.inventoryDeltas().stream().anyMatch(d->d.fingerprint().equals("minecraft:carrot#0")&&d.quantity()>0));
        if(!newHarvest||work.pendingCargo(npc.getUUID()).getOrDefault("minecraft:carrot#0",0)<1)return SEEDS;
        int count=npc.getPersonalInventory().count(s->s.is(Items.CARROT));
        return Math.max(1,Math.min(SEEDS,count-1));
    }

    /** Only starving members able to use the shared meal now take precedence; title is unchanged. */
    public static boolean pantryTurn(NpcBase npc){
        var b=forResident(npc);if(b==null)return true;
        var home=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());var life=NpcLifeData.get(MinecraftApi.server(npc));
        if(home==null)return true;
        var own=life.needs(npc.getCharacterId());int hunger=own==null?0:own.hunger();
        long now=npc.level().getGameTime();
        for(var member:home.members()){
            var person=member.characterId();if(person.equals(npc.getCharacterId()))continue;
            var other=life.needs(person);if(other==null||other.hunger()<800)continue;
            if(!(other.hunger()>hunger||other.hunger()==hunger&&person.compareTo(npc.getCharacterId())<0))continue;
            var body=CharacterRegistryData.get(MinecraftApi.server(npc)).bodyOf(person);
            var e=body==null||!body.dimensionId().equals(npc.level().dimension().identifier().toString())?null:
                    ((net.minecraft.server.level.ServerLevel)npc.level()).getEntity(body.entityId());
            if(e instanceof NpcBase peer&&person.equals(peer.getCharacterId())&&pantryContender(peer,home,now))return false;
        }
        return true;
    }

    /** Queries existing eligibility and routes; never starts movement, installs plans or grants rights. */
    private static boolean pantryContender(NpcBase peer,com.jgeted.sagadyssey.social.household.HouseholdRecord home,long now){
        if(!peer.isAlive()||peer.isRemoved()||peer.isNoAi()||peer.getTarget()!=null||!NpcBrainRuntime.isPlanned(peer)
                ||!peer.onGround()&&!peer.isSleeping())return false;
        var dimension=peer.level().dimension().identifier().toString();var at=home.sharedStorage();
        var record=NpcLifeData.get(MinecraftApi.server(peer)).record(peer.getCharacterId());
        if(record==null||!dimension.equals(record.dimension())||!dimension.equals(home.dimension())||at==null
                ||!peer.level().hasChunkAt(at)
                ||com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.protects(MinecraftApi.server(peer),dimension,at))return false;
        var activity=com.jgeted.sagadyssey.npc.brain.life.LifeActivityData.get(MinecraftApi.server(peer));
        if(now<activity.retryAt(peer.getUUID())||now<activity.placeRetryAt(peer.getUUID(),dimension,at))return false;
        var plan=NpcBrainRuntime.data(MinecraftApi.server(peer)).active(peer.getUUID()).orElse(null);
        if(plan!=null&&(plan.currentStep().phase()==com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase.SETTLING
                ||"PENDING".equals(activity.settlement(plan.id()+":"+plan.currentStepIndex()))
                ||isFarmer(peer)&&plan.providerId().equals(FieldworkPlanFactory.PROVIDER)
                    &&"PLANT".equals(plan.currentStep().params().get("operation"))))return false;
        if(!FieldworkData.get(MinecraftApi.server(peer)).outstandingScaffolds(peer.getUUID()).isEmpty())return false;
        var box=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(peer.level().getBlockEntity(at));
        if(box==null)return false;
        var id=ContainerIds.block("household_storage",dimension,at);var owner=SubjectRef.household(home.id());
        boolean food=false;
        for(int slot=0;slot<box.getContainerSize();slot++){
            var stack=box.getItem(slot);
            if(NpcFoodItems.canConsumePlanned(stack,true)&&usable(peer,box,id,owner,ItemFingerprint.of(stack),0,now)>0){food=true;break;}
        }
        if(!food)return false;
        if(FieldworkRoutes.closeEnough(peer,at))return true;
        var route=FieldworkRoutes.plan(peer,at,false);if(route==null)return false;
        if(peer.blockPosition().distSqr(route.stand())<=12D*12D)return true;
        // The segmented work planner only validates distant stand geometry. A distant resident
        // cannot reserve exclusive meal priority on that basis. Navigation queries may cache paths.
        var path=peer.getNavigation().createPath(route.stand(),0);
        return path!=null&&path.canReach();
    }

    public static int foodSlot(NpcBase npc,Container source,String kind,BlockPos position,boolean rawAllowed,long now){
        if(source==null)return -1;
        if(forResident(npc)==null)return NpcFoodItems.findPlannedFoodSlot(source,rawAllowed);
        var actor=SubjectRef.character(npc.getCharacterId());UUID id;
        if(kind.equals("BAG"))id=ContainerIds.npcPersonal(npc.getCharacterId());
        else if(kind.equals("HOUSEHOLD")){
            if(!pantryTurn(npc))return -1;
            var home=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
            if(home==null||!Objects.equals(position,home.sharedStorage())||!home.dimension().equals(npc.level().dimension().identifier().toString()))return -1;
            actor=SubjectRef.household(home.id());id=ContainerIds.block("household_storage",home.dimension(),position);
        }else id=ContainerIds.block("private_storage",npc.level().dimension().identifier().toString(),position);
        var rights=RightsStateData.get(MinecraftApi.server(npc));var context=rights.context(id);var resolver=new RightsService(rights);
        for(int i=0;i<source.getContainerSize();i++){
            var stack=source.getItem(i);if(!NpcFoodItems.canConsumePlanned(stack,rawAllowed))continue;
            var fp=ItemFingerprint.of(stack);
            // A not-yet-used personal bag is initialized by the existing commit path only.
            if(context==null){if(!kind.equals("BAG"))continue;}
            else if(resolver.can(actor,AssetRight.CONSUME,id,i,fp,now).status()!=RightsVerdict.Status.ALLOW)continue;
            int protectedQuantity=kind.equals("BAG")&&stack.is(Items.CARROT)&&isFarmer(npc)?eatingProtection(npc):0;
            if(usable(npc,source,id,actor,fp,protectedQuantity,now)>0)return i;
        }
        return -1;
    }
    /** Conservative quantity by beneficial owner; never creates rights, reservations or identities. */
    public static int usable(NpcBase npc,Container source,UUID id,SubjectRef actor,ItemFingerprint item,int protection,long now){
        var rights=RightsStateData.get(MinecraftApi.server(npc));var context=rights.context(id);
        if(context==null){
            if(!id.equals(ContainerIds.npcPersonal(npc.getCharacterId()))||!actor.equals(SubjectRef.character(npc.getCharacterId())))return 0;
            int physical=0;for(int i=0;i<source.getContainerSize();i++)if(item.matches(source.getItem(i)))physical+=source.getItem(i).getCount();
            return purposeAvailable(physical,protection,0,rights.reservedQuantity(id,item,null,now));
        }
        var resolver=new RightsService(rights);var owned=new HashMap<SubjectRef,Integer>();
        for(int i=0;i<source.getContainerSize();i++){
            var stack=source.getItem(i);if(!item.matches(stack)||resolver.can(actor,AssetRight.CONSUME,id,i,item,now).status()!=RightsVerdict.Status.ALLOW)continue;
            var claim=rights.claimAt(id,i,item);
            if(claim!=null&&(claim.quantity()!=stack.getCount()||!claim.encumbrances().isEmpty()||!claim.reservations().isEmpty()))continue;
            owned.merge(claim==null?context.defaultBeneficialOwner():claim.beneficialOwner(),stack.getCount(),Integer::sum);
        }
        int total=0;
        for(var e:owned.entrySet()){
            // A foreign stack must not make the farmer's protected own stack appear spendable.
            // The existing consumption service chooses slots; do not change its title semantics here.
            if(protection>0&&!e.getKey().equals(SubjectRef.character(npc.getCharacterId())))continue;
            int reserved=rights.reservedQuantity(id,item,e.getKey(),null,now);
            int same=verifiedPlantingReservation(npc,id,item,e.getKey(),now);
            int protect=e.getKey().equals(SubjectRef.character(npc.getCharacterId()))?protection:0;
            total+=purposeAvailable(e.getValue(),protect,same,Math.max(0,reserved-same));
        }
        return total;
    }
    private static int verifiedPlantingReservation(NpcBase npc,UUID id,ItemFingerprint item,SubjectRef owner,long now){
        if(!id.equals(ContainerIds.npcPersonal(npc.getCharacterId()))||!owner.equals(SubjectRef.character(npc.getCharacterId()))||!item.equals(new ItemFingerprint("minecraft:carrot",0)))return 0;
        var p=NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).orElse(null);
        if(p==null||!p.providerId().equals(FieldworkPlanFactory.PROVIDER))return 0;
        int pending=0;
        for(var step:p.steps())if(step.index()>=p.currentStepIndex()&&"PLANT".equals(step.params().get("operation"))&&"minecraft:carrot".equals(step.params().get("input"))
                &&FieldworkDiscovery.allowed(npc,FieldworkTarget.position(step.params()))&&FieldworkData.get(MinecraftApi.server(npc)).receipt(p.id()+":"+step.index()).isEmpty())pending++;
        if(pending==0)return 0;
        int quantity=RightsStateData.get(MinecraftApi.server(npc)).reservations().stream().filter(r->r.activeAt(now)&&r.containerId().equals(id)&&r.fingerprint().equals(item)&&r.reservedFor().equals(owner)&&r.token().equals("fieldwork:"+p.id())).mapToInt(AssetReservation::quantity).sum();
        return Math.min(pending,quantity);
    }
}
