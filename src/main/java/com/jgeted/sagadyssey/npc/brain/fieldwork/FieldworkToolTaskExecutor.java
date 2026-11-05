package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** One explicitly authorized spare pickaxe, from the current household to the original worker. */
public final class FieldworkToolTaskExecutor implements NpcTaskExecutor {
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    @Override public ResourceLocation id(){return FieldworkPlanFactory.TOOL;}
    @Override public void forget(UUID body,UUID plan){movement.remove(plan);}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return take(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return take(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return take(c,p,s);}
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){movement.remove(p.id());if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}

    static boolean hasUsablePickaxe(NpcBase npc,BlockState target,long now){
        var inventory=npc.getPersonalInventory();var rights=RightsStateData.get(npc.getServer());var id=ContainerIds.npcPersonal(npc.getCharacterId());
        for(int slot=0;slot<=inventory.getContainerSize();slot++){
            var item=slot==inventory.getContainerSize()?npc.getMainHandItem():inventory.getItem(slot);
            if(item.isEmpty()||!(item.getItem() instanceof PickaxeItem)||target.requiresCorrectToolForDrops()&&!item.isCorrectToolForDrops(target))continue;
            if(rights.context(id)==null)return true;
            var fp=ItemFingerprint.of(item);var claim=rights.claimAt(id,slot,fp);
            if(claim!=null&&!claim.reservations().isEmpty()||rights.reservedQuantity(id,fp,null,now)>0)continue;
            if(new RightsService(rights).can(SubjectRef.character(npc.getCharacterId()),AssetRight.USE,id,slot,fp,now).status()==RightsVerdict.Status.ALLOW)return true;
        }
        return false;
    }
    private TaskTickResult take(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("领取工具需要服务端实体");
        var npc=c.npc();var server=npc.getServer();long now=c.gameTime();
        var ledger=TransferLedgerData.get(server);var previous=ledger.receipt(plan.id());
        if(previous!=null)return previous.successful()?done(npc,plan,"已提交同一工具领取，未重复取物"):stop(npc,plan,"本次领取未提交，稍后重查条件");
        var target=FieldworkTarget.position(step.params());
        if(npc.getProfession()!=NpcProfession.WORKER||npc.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(npc)
                ||!FieldworkDiscovery.allowed(npc,target)||!c.level().hasChunkAt(target))return stop(npc,plan,"工作条件已变化，保留工具");
        var state=c.level().getBlockState(target);
        if(!FieldworkDiscovery.mineTarget(state)||hasUsablePickaxe(npc,state,now))return done(npc,plan,"无需再领铁镐");
        var home=HouseholdData.get(server).forMember(npc.getCharacterId());
        if(home==null||home.sharedStorage()==null||!home.id().toString().equals(step.params().get("household"))
                ||!home.dimension().equals(c.level().dimension().location().toString()))return stop(npc,plan,"家庭或工具仓关联已变化");
        var source=new FieldworkStorage(home.dimension(),home.sharedStorage(),ContainerIds.block("household_storage",home.dimension(),home.sharedStorage()),
                SubjectRef.household(home.id()),AccessPolicy.HOUSEHOLD,TransferIntent.GIFT,"worker_tool:"+npc.getCharacterId());
        var inventory=SupplyFlowService.container(server,source);
        if(inventory==null||!SupplyFlowService.validOwner(server,source))return stop(npc,plan,"家庭工具仓未加载或权属不符，等待补给");
        var rights=RightsStateData.get(server);var service=new RightsService(rights);var actor=SubjectRef.character(npc.getCharacterId());
        ItemStack chosen=ItemStack.EMPTY;
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var item=inventory.getItem(slot);
            if(!item.is(Items.IRON_PICKAXE)||item.getDamageValue()>=item.getMaxDamage()||state.requiresCorrectToolForDrops()&&!item.isCorrectToolForDrops(state))continue;
            var fp=ItemFingerprint.of(item);
            if(service.can(actor,AssetRight.ACCESS,source.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW
                    ||service.can(actor,AssetRight.USE,source.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW
                    ||SupplyFlowService.available(server,source,actor,fp,now)<1)continue;
            chosen=item;break;
        }
        if(chosen.isEmpty()){
            movement.remove(plan.id());npc.getNavigation().stop();String reason="缺少有领取和使用权限的备用铁镐，等待家庭工具补给";
            npc.reportWorkActivity("缺工具，等待补给",null,reason);
            return TaskTickResult.blocked(reason,new NpcPlanBlocker("MISSING_TOOL",reason,now+200,null));
        }
        if(FieldworkDepositTaskExecutor.capacity(npc.getPersonalInventory(),chosen)<1)return stop(npc,plan,"随身库存已满，无法领取工具");
        if(!FieldworkRoutes.closeEnough(npc,source.position())){
            var route=FieldworkRoutes.plan(npc,source.position(),false);
            if(route==null)return stop(npc,plan,"工具仓暂不可达");
            var progress=movement.computeIfAbsent(plan.id(),ignored->{var m=new MovementProgress();m.reset(npc,route.stand());return m;});
            if(progress.tick(npc,route.stand()))return stop(npc,plan,"前往工具仓暂时无法推进");
            if(now%20==0||npc.getNavigation().isDone())if(!FieldworkRoutes.move(npc,route.stand()))return stop(npc,plan,"工具仓暂不可达");
            npc.reportWorkActivity("领取备用铁镐",source.position(),"原工人前往授权家庭仓");return TaskTickResult.running("前往家庭工具仓");
        }
        var personal=ContainerIds.npcPersonal(npc.getCharacterId());rights.ensureContext(personal,"npc:"+npc.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        if(!rights.context(personal).defaultBeneficialOwner().equals(actor)||!rights.context(personal).custodian().equals(actor))return stop(npc,plan,"随身库存权属不符，保留家庭工具");
        // Server-thread atomic submission: a persisted plan ID is the transfer ID. A recovered
        // successful receipt ends this step before constructing another request or moving items.
        var request=new TransferRequest(plan.id(),actor,TransferIntent.GIFT,List.of(new TransferLeg(source.containerId(),personal,ItemFingerprint.of(chosen),1)),
                List.of(new ExpectedRevision(source.containerId(),rights.context(source.containerId()).revision()),new ExpectedRevision(personal,rights.context(personal).revision())),source.authority(),now);
        var receipt=new TransferService(rights,ledger).submit(request,Map.of(source.containerId(),new TransferEndpoint(source.containerId(),inventory),personal,new TransferEndpoint(personal,npc.getPersonalInventory())),now);
        return receipt.successful()?done(npc,plan,"备用铁镐已合法转入原工人随身库存"):stop(npc,plan,"领取未提交："+receipt.reason());
    }
    private TaskTickResult done(NpcBase npc,NpcPlan plan,String reason){movement.remove(plan.id());npc.getNavigation().stop();npc.reportWorkActivity("领取工具",null,reason);return TaskTickResult.stepSucceeded(reason);}
    private TaskTickResult stop(NpcBase npc,NpcPlan plan,String reason){movement.remove(plan.id());npc.getNavigation().stop();npc.reportWorkActivity("缺工具，等待补给",null,reason);return TaskTickResult.failed(reason);}
}
