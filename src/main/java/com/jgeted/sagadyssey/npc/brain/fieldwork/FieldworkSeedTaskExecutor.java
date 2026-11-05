package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;

/** Existing household wheat pickup, plus one carrot from a frozen authorized source for the v3 farmer. */
public final class FieldworkSeedTaskExecutor implements NpcTaskExecutor {
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    @Override public ResourceLocation id(){return FieldworkPlanFactory.SEED;}
    @Override public void forget(UUID body,UUID plan){movement.remove(plan);}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return take(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return take(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return take(c,p,s);}
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){movement.remove(p.id());if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}

    /** A real ready cell in CROP mode; existing usable planting choices keep priority. */
    static FieldworkTarget shortageTarget(NpcBase npc,net.minecraft.core.BlockPos pos){
        if(npc.getProfession()!=NpcProfession.FARMER||npc.getFarmMode()!=FarmMode.CROP
                ||!npc.level().hasChunkAt(pos)||!FieldworkDiscovery.allowed(npc,pos))return null;
        for(var seed:List.of(Items.WHEAT_SEEDS,Items.CARROT,Items.POTATO,Items.BEETROOT_SEEDS))if(FieldworkDiscovery.has(npc,seed))return null;
        boolean carrot=NaturalFarmRules.isFarmer(npc);
        var before=npc.level().getBlockState(pos);var crop=(carrot?net.minecraft.world.level.block.Blocks.CARROTS:net.minecraft.world.level.block.Blocks.WHEAT).defaultBlockState();
        if(!before.isAir()||!npc.level().getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)||!crop.canSurvive(npc.level(),pos))return null;
        return new FieldworkTarget(pos,FieldworkAction.Operation.PLANT,before,crop,carrot?Items.CARROT:Items.WHEAT_SEEDS,null);
    }

    /** Selection reads registered knowledge and existing rights; it never grants access or reserves stock. */
    static FieldworkStorage carrotSource(NpcBase npc,UUID household,long now){
        if(!NaturalFarmRules.isFarmer(npc))return null;
        var home=HouseholdData.get(npc.getServer()).forMember(npc.getCharacterId());
        if(home==null||!home.id().equals(household))return null;
        var b=NaturalFarmRules.forResident(npc);var flows=SupplyFlowData.get(npc.getServer());
        for(var position:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY)){
            boolean raw=position.equals(NaturalVillageLayout.RAW);
            var at=NaturalVillageLayout.at(b,position);
            UUID id=ContainerIds.block(raw?"private_storage":"household_storage",b.dimension(),at);
            if(!flows.known(SubjectRef.household(home.id())).contains(id))continue;
            var point=flows.point(id);
            if(!validCarrotSource(npc,home.id(),point))continue;
            var inventory=SupplyFlowService.container(npc.getServer(),point);
            if(inventory!=null&&!usableSeed(npc,point,inventory,Items.CARROT,true,now).isEmpty())return point;
        }
        return null;
    }

    private static boolean validCarrotSource(NpcBase npc,UUID household,FieldworkStorage point){
        if(!NaturalFarmRules.isFarmer(npc))return false;
        var server=npc.getServer();var b=NaturalFarmRules.forResident(npc);
        var home=HouseholdData.get(server).forMember(npc.getCharacterId());
        if(home==null||!home.id().equals(household)||!Objects.equals(home.settlementId(),b.settlementId())
                ||!home.dimension().equals(b.dimension())||!point.dimension().equals(b.dimension())
                ||!SupplyFlowData.get(server).known(SubjectRef.household(home.id())).contains(point.containerId())
                ||!SupplyFlowService.validOwner(server,point))return false;
        var raw=NaturalVillageLayout.at(b,NaturalVillageLayout.RAW);
        var pantry=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);
        if(point.position().equals(raw)){
            var life=NpcLifeData.get(server).record(npc.getCharacterId());
            return life!=null&&life.dimension().equals(b.dimension())&&raw.equals(life.privateStoragePosition())
                    &&point.containerId().equals(ContainerIds.block("private_storage",b.dimension(),raw))
                    &&point.owner().equals(SubjectRef.character(npc.getCharacterId()))&&point.policy()==AccessPolicy.PRIVATE;
        }
        return point.position().equals(pantry)&&pantry.equals(home.sharedStorage())
                &&point.containerId().equals(ContainerIds.block("household_storage",b.dimension(),pantry))
                &&point.owner().equals(SubjectRef.household(home.id()))&&point.policy()==AccessPolicy.HOUSEHOLD;
    }

    private static ItemStack usableSeed(NpcBase npc,FieldworkStorage source,net.minecraft.world.Container inventory,
                                        Item wanted,boolean strict,long now){
        var service=new RightsService(RightsStateData.get(npc.getServer()));
        var actor=SubjectRef.character(npc.getCharacterId());
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var item=inventory.getItem(slot);if(!item.is(wanted))continue;var fp=ItemFingerprint.of(item);
            if(service.can(actor,AssetRight.ACCESS,source.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW
                    ||service.can(actor,AssetRight.CONSUME,source.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW
                    ||strict&&service.can(actor,AssetRight.TRANSFER,source.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW
                    ||SupplyFlowService.available(npc.getServer(),source,actor,fp,now)<1)continue;
            return item;
        }
        return ItemStack.EMPTY;
    }

    private TaskTickResult take(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("领取种子需要服务端实体");
        var npc=c.npc();var server=npc.getServer();long now=c.gameTime();
        var ledger=TransferLedgerData.get(server);var previous=ledger.receipt(plan.id());
        if(previous!=null)return previous.successful()?done(npc,plan,"已提交同一种子领取，未重复取物"):stop(npc,plan,"本次领取未提交，稍后重查条件");
        var target=FieldworkTarget.position(step.params());
        if(npc.getProfession()!=NpcProfession.FARMER||npc.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(npc)
                ||!FieldworkDiscovery.allowed(npc,target)||!c.level().hasChunkAt(target))return stop(npc,plan,"工作条件已变化，保留种子");
        boolean carrot=step.params().containsKey("seed_source");
        if(carrot&&!NaturalFarmRules.isFarmer(npc))return stop(npc,plan,"原自然农民身份或岗位已变化，保留来源库存");
        if(shortageTarget(npc,target)==null)return done(npc,plan,"无需再领种用物资");
        var home=HouseholdData.get(server).forMember(npc.getCharacterId());
        if(home==null||home.sharedStorage()==null||!home.id().toString().equals(step.params().get("household"))
                ||!home.dimension().equals(c.level().dimension().location().toString()))return stop(npc,plan,"家庭或种子仓关联已变化");
        FieldworkStorage source;
        if(carrot){
            try{
                source=SupplyFlowData.get(server).point(UUID.fromString(step.params().get("seed_source")));
                var frozen=new net.minecraft.core.BlockPos(Integer.parseInt(step.params().get("seed_x")),Integer.parseInt(step.params().get("seed_y")),Integer.parseInt(step.params().get("seed_z")));
                if(!source.dimension().equals(step.params().get("seed_dimension"))||!source.position().equals(frozen)
                        ||!"minecraft:carrot".equals(step.params().get("input"))||!validCarrotSource(npc,home.id(),source))
                    return waitForSeed(npc,plan,"原种用来源、知识或权属已变化，等待重新发现",now);
            }catch(IllegalArgumentException|NoSuchElementException ex){return stop(npc,plan,"原种用来源记录无效，未另选来源或扣物");}
        }else source=new FieldworkStorage(home.dimension(),home.sharedStorage(),ContainerIds.block("household_storage",home.dimension(),home.sharedStorage()),
                SubjectRef.household(home.id()),AccessPolicy.HOUSEHOLD,TransferIntent.GIFT,"farmer_seed:"+npc.getCharacterId());
        var inventory=SupplyFlowService.container(server,source);
        if(inventory==null||!SupplyFlowService.validOwner(server,source))return carrot
                ?waitForSeed(npc,plan,"原种用仓未加载或权属不符，等待补给",now):stop(npc,plan,"家庭种子仓未加载或权属不符，等待补给");
        var rights=RightsStateData.get(server);var actor=SubjectRef.character(npc.getCharacterId());
        var chosen=usableSeed(npc,source,inventory,carrot?Items.CARROT:Items.WHEAT_SEEDS,carrot,now);
        if(chosen.isEmpty()){
            return waitForSeed(npc,plan,carrot?"原来源没有有权领取的种用胡萝卜，等待补给":"缺少有领取和使用权限的备用小麦种子，等待家庭种子补给",now);
        }
        if(FieldworkDepositTaskExecutor.capacity(npc.getPersonalInventory(),chosen)<1)return stop(npc,plan,"随身库存已满，无法领取种子");
        if(!FieldworkRoutes.closeEnough(npc,source.position())){
            var route=FieldworkRoutes.plan(npc,source.position(),false);
            if(route==null)return stop(npc,plan,"种子仓暂不可达");
            var progress=movement.computeIfAbsent(plan.id(),ignored->{var m=new MovementProgress();m.reset(npc,route.stand());return m;});
            if(progress.tick(npc,route.stand()))return stop(npc,plan,"前往种子仓暂时无法推进");
            if(now%20==0||npc.getNavigation().isDone())if(!FieldworkRoutes.move(npc,route.stand()))return stop(npc,plan,"种子仓暂不可达");
            npc.reportWorkActivity(carrot?"领取种用胡萝卜":"领取备用小麦种子",source.position(),carrot?"原农民前往计划已固定的授权来源":"原农民前往授权家庭仓");return TaskTickResult.running("前往种用物资仓");
        }
        var personal=ContainerIds.npcPersonal(npc.getCharacterId());rights.ensureContext(personal,"npc:"+npc.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        if(!rights.context(personal).defaultBeneficialOwner().equals(actor)||!rights.context(personal).custodian().equals(actor))return stop(npc,plan,"随身库存权属不符，保留家庭种子");
        // Server-thread atomic submission: a persisted plan ID is the transfer ID. A recovered
        // successful receipt ends this step before constructing another request or moving items.
        var request=new TransferRequest(plan.id(),actor,TransferIntent.GIFT,List.of(new TransferLeg(source.containerId(),personal,ItemFingerprint.of(chosen),1)),
                List.of(new ExpectedRevision(source.containerId(),rights.context(source.containerId()).revision()),new ExpectedRevision(personal,rights.context(personal).revision())),"farmer_seed:"+npc.getCharacterId(),now);
        var receipt=new TransferService(rights,ledger).submit(request,Map.of(source.containerId(),new TransferEndpoint(source.containerId(),inventory),personal,new TransferEndpoint(personal,npc.getPersonalInventory())),now);
        return receipt.successful()?done(npc,plan,carrot?"一份种用胡萝卜已合法转入原农民随身库存，等待正常种植":"备用小麦种子已合法转入原农民随身库存"):stop(npc,plan,"领取未提交："+receipt.reason());
    }
    private TaskTickResult waitForSeed(NpcBase npc,NpcPlan plan,String reason,long now){
        movement.remove(plan.id());npc.getNavigation().stop();npc.reportWorkActivity("缺种子，等待补给",null,reason);
        // Re-enter ordinary discovery after a bounded wait: a crop may have matured.
        if(now>=plan.createdAt()+200)return done(npc,plan,"补给暂不可用，重新检查既有作物与其他工作");
        return TaskTickResult.blocked(reason,new NpcPlanBlocker("MISSING_SEED",reason,plan.createdAt()+200,null));
    }
    private TaskTickResult done(NpcBase npc,NpcPlan plan,String reason){movement.remove(plan.id());npc.getNavigation().stop();npc.reportWorkActivity("领取种子",null,reason);return TaskTickResult.stepSucceeded(reason);}
    private TaskTickResult stop(NpcBase npc,NpcPlan plan,String reason){movement.remove(plan.id());npc.getNavigation().stop();npc.reportWorkActivity("缺种子，等待补给",null,reason);return TaskTickResult.failed(reason);}
}
