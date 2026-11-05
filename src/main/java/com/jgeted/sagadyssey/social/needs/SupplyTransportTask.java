package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.logistics.*;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import java.util.*;

/** Persistent travel/pickup/carry/delivery. Real custody survives pauses, failures and world reload. */
public final class SupplyTransportTask implements NpcTaskExecutor {
    public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:supply_transport");
    public static final ResourceLocation PROVIDER=ResourceLocation.parse("sagadyssey:supply");
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    private final Map<String,net.minecraft.core.BlockPos> regionalWaypoints=new HashMap<>();
    public static boolean eligible(NpcBase npc){return npc.isAlive()&&NpcBrainRuntime.isPlanned(npc)
            &&npc.getCommand()==NpcCommand.WORK&&npc.getTarget()==null&&npc.getTransportSlotLimit()>0;}
    public static boolean accepts(NpcBase npc,SupplyFlowData.Route route){
        if(route.carrierEntity()!=null){
            if(!route.carrierEntity().equals(npc.getUUID())||!route.carrierCharacter().equals(npc.getCharacterId()))return false;
            // Preserve custody after employment ends, but recheck work authority before pickup.
            if(route.stage()==SupplyFlowData.Stage.CARRYING||route.pending()!=null)return true;
        }
        var authority=NpcWorkContext.from(npc);var principal=route.principal();
        return switch(principal.type()){
            case CHARACTER -> principal.id().equals(npc.getCharacterId())||principal.id().equals(authority.employerCharacterId());
            case INSTITUTION -> principal.id().equals(authority.workplaceSettlementId())
                    || com.jgeted.sagadyssey.social.religion.ReligionTask.canCarrySupply(npc,route);
            case HOUSEHOLD -> {var home=HouseholdData.get(npc.getServer()).byId(principal.id());yield home!=null&&(home.contains(npc.getCharacterId())||home.headCharacterId().equals(authority.employerCharacterId()));}
            case CONTAINER -> false;
        };
    }
    public static Optional<NpcPlan> choose(NpcBase npc,long now){
        if(!eligible(npc))return Optional.empty();
        var flows=SupplyFlowData.get(npc.getServer());
        var route=flows.routes().stream().filter(r->!r.closed()&&r.retryAt()<=now&&accepts(npc,r))
                .filter(r->NpcSchedulePlanner.allowsWork(npc)||urgentOwnFood(npc,r)||urgentFamilyFood(npc,r))
                .filter(r->flows.point(r.source()).dimension().equals(npc.level().dimension().location().toString()))
                .sorted(Comparator.comparing((SupplyFlowData.Route r)->r.stage()!=SupplyFlowData.Stage.CARRYING).thenComparing(r->r.id().toString())).findFirst().orElse(null);
        if(route==null)return Optional.empty();route=route.claim(npc.getUUID(),npc.getCharacterId());flows.route(route);
        LogisticsData.get(npc.getServer()).claimManagedOrder(route.id(),npc.getCharacterId(),now);
        var steps=new ArrayList<NpcPlanStep>();
        if(route.stage()==SupplyFlowData.Stage.HELD){step(steps,NpcPlanPhase.TRAVELLING,"source",route,now);step(steps,NpcPlanPhase.ACTING,"pickup",route,now);}
        step(steps,NpcPlanPhase.SETTLING,"destination",route,now);step(steps,NpcPlanPhase.SETTLING,"deliver",route,now);
        return Optional.of(new NpcPlan(UUID.randomUUID(),npc.getUUID(),"need-route:"+route.id(),PROVIDER,NpcPlanKind.COMMITMENT,
                NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,steps,0,now,now,now+40,now+12000,null,"履行真实物资交付义务"));
    }
    /** A starving resident may finish its own household's supported food supply, never unrelated work. */
    private static boolean urgentOwnFood(NpcBase npc,SupplyFlowData.Route route){
        if(!com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.ordinaryRawDiet(npc))return false;
        var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(npc.getServer()).needs(npc.getCharacterId());
        if(needs==null||needs.hunger()<800||route.principal().type()!=SubjectRef.SubjectType.HOUSEHOLD)return false;
        if(SettlementExchangeData.emergencyFood(npc,route))return true;
        var home=HouseholdData.get(npc.getServer()).byId(route.principal().id());
        var need=NeedRequestData.get(npc.getServer()).request(route.need());
        return home!=null&&home.contains(npc.getCharacterId())&&need!=null&&need.key().item().itemId().equals("minecraft:carrot");
    }
    private static boolean urgentFamilyFood(NpcBase npc,SupplyFlowData.Route route){
        var b=com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc);
        if(b==null||!npc.getCharacterId().equals(com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.person(b,1))
                ||!route.principal().equals(SubjectRef.household(com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.home(b)))
                ||!com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodEmergency(npc))return false;
        var need=NeedRequestData.get(npc.getServer()).request(route.need());
        return need!=null&&need.key().item().equals(new ItemFingerprint("minecraft:carrot",0));
    }
    /** Read-only admission before waking a sleeper. Calling choose here would claim work too early. */
    public static boolean hasEmergencyRoute(NpcBase npc,long now){
        if(!eligible(npc))return false;var server=npc.getServer();var flows=SupplyFlowData.get(server);
        for(var route:flows.routes()){
            if(route.closed()||route.pending()!=null||route.retryAt()>now||!accepts(npc,route)||!urgentFamilyFood(npc,route))continue;
            var source=flows.point(route.source());var destination=flows.point(route.destination());
            if(!source.dimension().equals(npc.level().dimension().location().toString()))continue;
            var target=SupplyFlowService.container(server,destination);
            if(target==null||!SupplyFlowService.validOwner(server,destination)||FieldworkDepositTaskExecutor.capacity(target,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT))<route.quantity()
                    ||FieldworkRoutes.plan(npc,destination.position(),false)==null)continue;
            if(route.stage()==SupplyFlowData.Stage.CARRYING&&SupplyFlowService.count(npc.getTransportInventory(),new ItemFingerprint("minecraft:carrot",0))>=route.quantity())return true;
            var inventory=SupplyFlowService.container(server,source);
            if(inventory==null||!SupplyFlowService.validOwner(server,source)||SupplyFlowService.count(inventory,new ItemFingerprint("minecraft:carrot",0))<route.quantity()
                    ||FieldworkRoutes.plan(npc,source.position(),false)==null)continue;
            var rights=RightsStateData.get(server);var resolver=new RightsService(rights);
            for(int slot=0;slot<inventory.getContainerSize();slot++)if(inventory.getItem(slot).is(net.minecraft.world.item.Items.CARROT)
                    &&resolver.can(route.principal(),AssetRight.TRANSFER,source.containerId(),slot,ItemFingerprint.of(inventory.getItem(slot)),now).status()==RightsVerdict.Status.ALLOW)return true;
        }
        return false;
    }
    private static void step(List<NpcPlanStep> steps,NpcPlanPhase phase,String op,SupplyFlowData.Route route,long now){
        steps.add(new NpcPlanStep(steps.size(),phase,ID,Map.of("route",route.id().toString(),"operation",op),NpcPlanStepStatus.PENDING,now,"实际搬运与交付"));
    }
    @Override public ResourceLocation id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return tick(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("运输需要服务端实体");
        var npc=c.npc();var server=npc.getServer();var flows=SupplyFlowData.get(server);long now=c.gameTime();
        var route=flows.route(UUID.fromString(step.params().get("route")));
        if(route.stage()==SupplyFlowData.Stage.DONE)return TaskTickResult.stepSucceeded("交付回执已经登记");
        if(!SettlementExchangeData.validRoute(server,route.id()))return defer(c,plan,route,"交换接收方迁居、解散或义务待核对，保留货物",false);
        if(route.closed())return TaskTickResult.failed(route.detail());
        if(!eligible(npc)||!accepts(npc,route))return defer(c,plan,route,"工作权威或明确命令改变，保留货物",false);
        if(route.pending()!=null){
            var receipt=TransferLedgerData.get(server).receipt(route.pending().id());
            if(receipt!=null){route=reconcile(c,route,receipt);if(!receipt.successful())return defer(c,plan,route,"转移条件变化，保留物资重核",false);}
            else if(now>route.pending().time()+2){flows.route(route.progress(SupplyFlowData.Stage.REVIEW,route.pending(),now,route.failures(),"中断转移没有可验证回执，需人工核对"));return TaskTickResult.failed("中断转移待核对，不自动重放");}
        }
        var operation=step.params().get("operation");
        if(operation.equals("pickup")&&route.stage()==SupplyFlowData.Stage.CARRYING)return TaskTickResult.stepSucceeded("取货已提交");
        if(operation.equals("deliver")&&route.stage()==SupplyFlowData.Stage.DONE)return TaskTickResult.stepSucceeded("交付已提交");
        var point=flows.point(operation.equals("source")||operation.equals("pickup")?route.source():route.destination());
        boolean regional=SettlementExchangeData.tradeRoute(server,route.id());
        if(regional&&point.dimension().equals(c.level().dimension().location().toString())&&npc.blockPosition().distSqr(point.position())>24*24){
            if(!npc.onGround())return TaskTickResult.running("等待安全落地后继续区域商路");
            String waypointKey=plan.id()+":"+step.index();var waypoint=regionalWaypoints.get(waypointKey);
            if(waypoint==null||npc.blockPosition().distSqr(waypoint)<=16){waypoint=SettlementExchangeData.waypoint(npc,point.position());if(waypoint!=null)regionalWaypoints.put(waypointKey,waypoint);}
            if(waypoint==null)return TaskTickResult.running("等待商路前方区块或可通行道路");
            final var currentWaypoint=waypoint;
            var progress=movement.computeIfAbsent(plan.id(),id->{var value=new MovementProgress();value.reset(npc,currentWaypoint);return value;});
            if(progress.tick(npc,waypoint))return defer(c,plan,route,"区域商路受阻，保留真实货物",true);
            if(now%20==0||npc.getNavigation().isDone())if(!FieldworkRoutes.move(npc,waypoint))return defer(c,plan,route,"区域商路路径不可达，保留真实货物",true);
            npc.reportWorkActivity("履行聚落实物交换",point.position(),"沿已知路线实际往返");return TaskTickResult.running("分段前往已约定的接收点");
        }
        var target=SupplyFlowService.container(server,point);
        if(regional&&target==null)return TaskTickResult.running("等待接收仓储恢复，物资仍在原处或原货舱");
        if(!point.dimension().equals(c.level().dimension().location().toString())||target==null||!SupplyFlowService.validOwner(server,point))
            return defer(c,plan,route,"目标失效、未加载或所有权变化",false);
        if(!FieldworkRoutes.closeEnough(npc,point.position())){
            if(!npc.onGround())return TaskTickResult.running("等待安全落地后继续搬运");
            var path=FieldworkRoutes.plan(npc,point.position(),false);if(path==null)return defer(c,plan,route,"运输路径不可达",true);
            var progress=movement.computeIfAbsent(plan.id(),id->{var value=new MovementProgress();value.reset(npc,path.stand());return value;});
            if(progress.tick(npc,path.stand()))return defer(c,plan,route,"运输路线连续无法推进",true);
            if(now%20==0||npc.getNavigation().isDone())if(!FieldworkRoutes.move(npc,path.stand()))return defer(c,plan,route,"运输路径不可达",true);
            npc.reportWorkActivity(route.stage()==SupplyFlowData.Stage.CARRYING?"携带货物前往交付":"前往取货",point.position(),"真实运输任务");return TaskTickResult.running("运输中");
        }
        npc.getNavigation().stop();movement.remove(plan.id());regionalWaypoints.keySet().removeIf(k->k.startsWith(plan.id()+":"));
        if(operation.equals("source")||operation.equals("destination"))return TaskTickResult.stepSucceeded("到达容器实际交互范围");
        boolean pickup=operation.equals("pickup");var need=NeedRequestData.get(server).request(route.need());var rights=RightsStateData.get(server);
        UUID bagId=ContainerIds.npcCargo(npc.getCharacterId());SubjectRef actor=SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(bagId,"npc:"+npc.getCharacterId()+":cargo",actor,actor,AccessPolicy.PRIVATE);
        if(!rights.context(bagId).defaultBeneficialOwner().equals(actor))return defer(c,plan,route,"货舱权属已变，停止取货",false);
        Container bag=new LimitedContainerView(npc.getTransportInventory(),npc.getTransportSlotLimit());
        UUID routeSource=route.source();
        if(pickup&&rights.reservationsForToken(route.token(),now).stream().filter(r->r.containerId().equals(routeSource)).mapToInt(AssetReservation::quantity).sum()<route.quantity()){
            SupplyFlowService.release(server,route,now,"源库存预留已失效，重新匹配");return TaskTickResult.failed("源库存预留失效");
        }
        if(route.pending()==null){
            var pending=new SupplyFlowData.Pending(UUID.randomUUID(),pickup,bagId,route.quantity(),need.key().item(),
                    rights.context(pickup?point.containerId():bagId).revision(),rights.context(pickup?bagId:point.containerId()).revision(),now);
            route=route.progress(route.stage(),pending,now,route.failures(),"转移意图已保存");flows.route(route);
        }
        var receipt=new TransferService(rights,TransferLedgerData.get(server)).submit(route.pending().request(route),
                Map.of(point.containerId(),new TransferEndpoint(point.containerId(),target),bagId,new TransferEndpoint(bagId,bag)),now);
        route=reconcile(c,route,receipt);
        if(!receipt.successful())return defer(c,plan,route,"容器满、货物或权限变化，保留货物稍后重核",false);
        npc.playAction(NpcAction.TRADE_PRESENT,20);return TaskTickResult.stepSucceeded(pickup?"真实取货完成":"真实入库完成");
    }
    private SupplyFlowData.Route reconcile(NpcEntityExecutionContext c,SupplyFlowData.Route route,TransferReceipt receipt){
        var pending=route.pending();if(pending==null||!pending.id().equals(receipt.transferId()))throw new IllegalStateException("transport receipt mismatch");
        var server=c.npc().getServer();long now=c.gameTime();var flows=SupplyFlowData.get(server);
        if(receipt.successful()){
            var needs=NeedRequestData.get(server);needs.acknowledge(pending.id(),route.need(),pending.pickup()?NeedRequestData.Action.DISPATCH:NeedRequestData.Action.DELIVER,pending.quantity(),now);
            var data=LogisticsData.get(server);var order=data.order(route.id());
            if(pending.pickup()){
                if(order.pickedUpCount()<route.quantity())data.recordPickup(route.id(),c.npc().getCharacterId(),route.quantity()-order.pickedUpCount(),now);
                var rights=RightsStateData.get(server);var owner=SubjectRef.character(c.npc().getCharacterId());
                if(!rights.reserve(new AssetReservation(SupplyFlowService.receipt(route.id(),"cargo-lease"),pending.bag(),pending.item(),route.quantity(),owner,route.token(),"真实在途货物",now,now+1200),SupplyFlowService.count(c.npc().getTransportInventory(),pending.item()),now))throw new IllegalStateException("carried stock commitment conflict");
            }else{
                if(order.deliveredCount()<route.quantity())data.recordDelivery(server,route.id(),c.npc().getCharacterId(),route.quantity()-order.deliveredCount(),now);
                RightsStateData.get(server).releaseReservations(route.token());
            }
            route=route.progress(pending.pickup()?SupplyFlowData.Stage.CARRYING:SupplyFlowData.Stage.DONE,null,now,route.failures(),pending.pickup()?"真实货物已在货舱":"成品已进入目标容器");
        }else route=route.progress(route.stage(),null,now,route.failures(),receipt.reason());
        flows.route(route);return route;
    }
    private TaskTickResult defer(NpcEntityExecutionContext c,NpcPlan plan,SupplyFlowData.Route route,String reason,boolean path){
        movement.remove(plan.id());regionalWaypoints.keySet().removeIf(k->k.startsWith(plan.id()+":"));c.npc().getNavigation().stop();long now=c.gameTime();var server=c.npc().getServer();
        int failures=route.failures()+(path?1:0);
        SupplyFlowData.get(server).route(route.progress(route.stage(),route.pending(),now+600,failures,reason));
        if(path&&route.stage()==SupplyFlowData.Stage.HELD&&route.pending()==null&&failures>=3)SupplyFlowService.release(server,route,now,reason);
        var needs=NeedRequestData.get(server);var request=needs.request(route.need());
        if(!request.status().terminal()&&(request.status()==NeedRequestStatus.MATCHED||request.status()==NeedRequestStatus.IN_TRANSIT||request.status()==NeedRequestStatus.OPEN))needs.transition(route.need(),NeedRequestStatus.UNREACHABLE,now,reason);
        c.npc().reportWorkActivity("保管运输物资并等待",null,reason);return TaskTickResult.failed(reason);
    }
    @Override public void cancel(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step,PlanCancellationReason reason){
        movement.remove(plan.id());regionalWaypoints.keySet().removeIf(k->k.startsWith(plan.id()+":"));if(raw instanceof NpcEntityExecutionContext c){c.npc().getNavigation().stop();var data=SupplyFlowData.get(c.npc().getServer());var route=data.route(UUID.fromString(step.params().get("route")));
            if(!route.closed())data.route(route.progress(route.stage(),route.pending(),c.gameTime()+200,route.failures(),"暂停计划，保留交付义务"));}
    }
}
