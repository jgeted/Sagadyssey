package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import java.util.*;

/** Merchant owns purchased goods, pays real money, carries it, and only earns on a real resale. */
public final class MerchantSupplyTask implements NpcTaskExecutor {
    public static final Identifier ID=Identifier.parse("sagadyssey:merchant_supply");
    public static final Identifier PROVIDER=Identifier.parse("sagadyssey:merchant_supply");
    private static final ItemFingerprint MONEY=new ItemFingerprint("minecraft:emerald",0);
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    public static Optional<NpcPlan> choose(NpcBase npc,long now){
        if(!SupplyTransportTask.eligible(npc)||npc.getProfession()!=NpcProfession.TRADER||!NpcSchedulePlanner.allowsWork(npc))return Optional.empty();
        var data=MerchantSupplyData.get(MinecraftApi.server(npc));
        var current=data.journeys().stream().filter(j->j.merchantEntity().equals(npc.getUUID())&&j.stage()!=MerchantSupplyData.Stage.DONE&&j.stage()!=MerchantSupplyData.Stage.REVIEW).findFirst().orElse(null);
        if(current!=null&&current.retryAt()>now)return Optional.empty();
        if(current==null){
            // Only merchant-local observations participate; remote inventory is never read here.
            var known=data.known(npc.getCharacterId()).stream().filter(o->o.time()<=now&&now-o.time()<=24000&&o.available()>0).sorted(Comparator.comparing(o->o.offer().toString())).toList();
            outer:for(var buyMemory:known){var buy=data.offer(buyMemory.offer());if(!buy.sells()||buy.expiresAt()<=now)continue;
                for(var sellMemory:known){var sell=data.offer(sellMemory.offer());if(sell.sells()||sell.expiresAt()<=now||sell.settlement().equals(buy.settlement())||!sell.item().equals(buy.item())||sell.unitEmeralds()<=buy.unitEmeralds())continue;
                    var points=SupplyFlowData.get(MinecraftApi.server(npc));var a=points.point(buy.point());var b=points.point(sell.point());
                    if(!a.dimension().equals(npc.level().dimension().identifier().toString())||!a.dimension().equals(b.dimension())||a.containerId().equals(b.containerId()))continue;
                    int quantity=Math.min(Math.min(buyMemory.available(),sellMemory.available()),SupplyFlowService.count(npc.getPersonalInventory(),MONEY)/buy.unitEmeralds());
                    if(quantity<1)continue;current=new MerchantSupplyData.Journey(UUID.randomUUID(),npc.getUUID(),npc.getCharacterId(),buy.id(),sell.id(),quantity,MerchantSupplyData.Stage.BUY,null,0,0,0,now,now,"依据本人已知供需报价选择路线");data.journey(current);break outer;
                }
            }
        }
        if(current==null)return Optional.empty();var steps=new ArrayList<NpcPlanStep>();
        if(current.stage()==MerchantSupplyData.Stage.BUY){step(steps,current,"source",NpcPlanPhase.TRAVELLING,now);step(steps,current,"buy",NpcPlanPhase.ACTING,now);}
        step(steps,current,"destination",NpcPlanPhase.SETTLING,now);step(steps,current,"sell",NpcPlanPhase.SETTLING,now);
        return Optional.of(new NpcPlan(UUID.randomUUID(),npc.getUUID(),current.token(),PROVIDER,NpcPlanKind.WORK,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,steps,0,now,now,now+40,now+12000,null,"买入、携带和真实卖出"));
    }
    private static void step(List<NpcPlanStep> steps,MerchantSupplyData.Journey j,String op,NpcPlanPhase phase,long now){steps.add(new NpcPlanStep(steps.size(),phase,ID,Map.of("journey",j.id().toString(),"operation",op),NpcPlanStepStatus.PENDING,now,"真实贸易路线"));}
    @Override public Identifier id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return tick(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("贸易需要实体");
        var npc=c.npc();var server=MinecraftApi.server(npc);var data=MerchantSupplyData.get(server);long now=c.gameTime();
        var j=data.journey(UUID.fromString(step.params().get("journey")));
        if(j.stage()==MerchantSupplyData.Stage.DONE)return TaskTickResult.stepSucceeded("真实交易已经完成");
        if(j.stage()==MerchantSupplyData.Stage.REVIEW)return TaskTickResult.failed(j.detail());
        if(!j.merchantEntity().equals(npc.getUUID())||!j.merchantCharacter().equals(npc.getCharacterId())||!SupplyTransportTask.eligible(npc))return defer(c,plan,j,"贸易中断，保留购买货物",false);
        if(j.transfer()!=null){var receipt=TransferLedgerData.get(server).receipt(j.transfer());
            if(receipt!=null){j=settle(c,j,receipt);if(!receipt.successful())return defer(c,plan,j,"交易条件变化，稍后核对",false);}
            else if(now>j.time()+2){data.journey(j.update(MerchantSupplyData.Stage.REVIEW,j.transfer(),j.personalRevision(),j.cargoRevision(),j.marketRevision(),j.time(),now,"中断交易缺少回执，不自动重放"));return TaskTickResult.failed("交易待人工核对");}}
        String op=step.params().get("operation");boolean buying=op.equals("source")||op.equals("buy");
        if(buying&&j.stage()==MerchantSupplyData.Stage.SELL)return TaskTickResult.stepSucceeded("买入回执已登记");
        if(j.stage()==MerchantSupplyData.Stage.DONE)return TaskTickResult.stepSucceeded("卖出回执已登记");
        var offer=data.offer(buying?j.buy():j.sell());var point=SupplyFlowData.get(server).point(offer.point());
        if(offer.expiresAt()<=now)return defer(c,plan,j,"已知报价到期，保留货物等待新约定",true);
        var market=SupplyFlowService.container(server,point);
        if(market==null||!SupplyFlowService.validOwner(server,point)||!point.dimension().equals(c.level().dimension().identifier().toString()))return defer(c,plan,j,"贸易目标失效或权属变化",false);
        if(!FieldworkRoutes.closeEnough(npc,point.position())){
            if(!npc.onGround())return TaskTickResult.running("等待安全落地后继续贸易路线");
            var path=FieldworkRoutes.plan(npc,point.position(),false);if(path==null)return defer(c,plan,j,"贸易路径不可达",false);
            var progress=movement.computeIfAbsent(plan.id(),id->{var value=new MovementProgress();value.reset(npc,path.stand());return value;});
            if(progress.tick(npc,path.stand()))return defer(c,plan,j,"贸易路径无法推进",false);
            if(now%20==0||npc.getNavigation().isDone())if(!FieldworkRoutes.move(npc,path.stand()))return defer(c,plan,j,"贸易路径不可达",false);
            npc.reportWorkActivity(buying?"前往已知余货市场":"携带商品前往已知需求市场",point.position(),"真实买卖路线");return TaskTickResult.running("实际前往市场");
        }
        npc.getNavigation().stop();movement.remove(plan.id());data.observe(npc,offer.id(),now);
        if(op.equals("source")||op.equals("destination"))return TaskTickResult.stepSucceeded("本人已到市场，更新本地供需观察");
        long committed=data.journeys().stream().filter(x->(buying?x.buy():x.sell()).equals(offer.id())&&
                (buying?x.stage()==MerchantSupplyData.Stage.SELL||x.stage()==MerchantSupplyData.Stage.DONE:x.stage()==MerchantSupplyData.Stage.DONE)).mapToLong(MerchantSupplyData.Journey::quantity).sum();
        if(committed+j.quantity()>offer.limit())return defer(c,plan,j,"报价可交易数量已经被履约",true);
        var rights=RightsStateData.get(server);UUID personal=ContainerIds.npcPersonal(npc.getCharacterId()),cargo=ContainerIds.npcCargo(npc.getCharacterId());var actor=SubjectRef.character(npc.getCharacterId());
        rights.ensureContext(personal,"npc:personal",actor,actor,AccessPolicy.PRIVATE);rights.ensureContext(cargo,"npc:cargo",actor,actor,AccessPolicy.PRIVATE);
        if(!rights.context(personal).defaultBeneficialOwner().equals(actor)||!rights.context(cargo).defaultBeneficialOwner().equals(actor))return defer(c,plan,j,"商人资产权属变化",false);
        Container bag=new LimitedContainerView(npc.getTransportInventory(),npc.getTransportSlotLimit());int payment=Math.multiplyExact(j.quantity(),offer.unitEmeralds());
        if(j.transfer()==null){
            UUID goodsSource=buying?point.containerId():cargo;Container goods=buying?market:bag;SubjectRef goodsOwner=buying?point.owner():actor;
            UUID moneySource=buying?personal:point.containerId();Container money=buying?npc.getPersonalInventory():market;SubjectRef moneyOwner=buying?actor:point.owner();
            if(!rights.reserve(new AssetReservation(SupplyFlowService.receipt(j.id(),"goods"),goodsSource,offer.item(),j.quantity(),goodsOwner,j.token(),"真实报价交易",now,now+1200),SupplyFlowService.count(goods,offer.item()),now)
                    ||!rights.reserve(new AssetReservation(SupplyFlowService.receipt(j.id(),"money"),moneySource,MONEY,payment,moneyOwner,j.token(),"真实报价付款",now,now+1200),SupplyFlowService.count(money,MONEY),now)){
                rights.releaseReservations(j.token());return defer(c,plan,j,"现货或真实绿宝石不足",false);}
            j=j.update(j.stage(),UUID.randomUUID(),rights.context(personal).revision(),rights.context(cargo).revision(),rights.context(point.containerId()).revision(),now,now,"买卖意图已持久化");data.journey(j);
        }
        var legs=buying?List.of(new TransferLeg(point.containerId(),cargo,offer.item(),j.quantity()),new TransferLeg(personal,point.containerId(),MONEY,payment))
                :List.of(new TransferLeg(cargo,point.containerId(),offer.item(),j.quantity()),new TransferLeg(point.containerId(),personal,MONEY,payment));
        var request=new TransferRequest(j.transfer(),actor,TransferIntent.PAYMENT,legs,List.of(new ExpectedRevision(personal,j.personalRevision()),new ExpectedRevision(cargo,j.cargoRevision()),new ExpectedRevision(point.containerId(),j.marketRevision())),j.token(),j.time(),Map.of(point.containerId(),point.owner()));
        var receipt=new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(personal,new TransferEndpoint(personal,npc.getPersonalInventory()),cargo,new TransferEndpoint(cargo,bag),point.containerId(),new TransferEndpoint(point.containerId(),market)),now);
        j=settle(c,j,receipt);if(!receipt.successful())return defer(c,plan,j,"交易未提交，保留钱货重核",false);
        npc.playAction(NpcAction.TRADE_PRESENT,20);return TaskTickResult.stepSucceeded(buying?"真实付款并买入商品":"真实交货并收到货款");
    }
    private MerchantSupplyData.Journey settle(NpcEntityExecutionContext c,MerchantSupplyData.Journey j,TransferReceipt receipt){
        if(!receipt.transferId().equals(j.transfer()))throw new IllegalStateException("wrong merchant receipt");var data=MerchantSupplyData.get(MinecraftApi.server(c.npc()));var rights=RightsStateData.get(MinecraftApi.server(c.npc()));
        rights.releaseReservations(j.token());var offer=data.offer(j.stage()==MerchantSupplyData.Stage.BUY?j.buy():j.sell());
        if(receipt.successful()){
            if(j.stage()==MerchantSupplyData.Stage.SELL&&data.settlement(j.id(),false)==null)
                SupplyFlowService.merchantDelivered(MinecraftApi.server(c.npc()),offer,receipt.transferId(),j.quantity(),c.gameTime());
            data.settlement(j.id(),j.stage()==MerchantSupplyData.Stage.BUY,receipt.transferId());
            if(j.stage()==MerchantSupplyData.Stage.BUY){UUID cargo=ContainerIds.npcCargo(j.merchantCharacter());var owner=SubjectRef.character(j.merchantCharacter());
                if(!rights.reserve(new AssetReservation(SupplyFlowService.receipt(j.id(),"cargo"),cargo,offer.item(),j.quantity(),owner,j.token(),"已付款在途商品",c.gameTime(),c.gameTime()+24000),SupplyFlowService.count(c.npc().getTransportInventory(),offer.item()),c.gameTime()))throw new IllegalStateException("merchant cargo commitment conflict");}
            j=j.update(j.stage()==MerchantSupplyData.Stage.BUY?MerchantSupplyData.Stage.SELL:MerchantSupplyData.Stage.DONE,null,0,0,0,c.gameTime(),c.gameTime(),"真实买卖回执已登记");
            int remaining=Math.max(0,data.known(j.merchantCharacter()).stream().filter(o->o.offer().equals(offer.id())).findFirst().map(MerchantSupplyData.Observation::available).orElse(0)-j.quantity());
            data.reveal(j.merchantCharacter(),new MerchantSupplyData.Observation(offer.id(),remaining,c.gameTime()));
        }else j=j.update(j.stage(),null,0,0,0,c.gameTime(),c.gameTime()+600,receipt.reason());data.journey(j);return j;
    }
    private TaskTickResult defer(NpcEntityExecutionContext c,NpcPlan p,MerchantSupplyData.Journey j,String reason,boolean review){movement.remove(p.id());c.npc().getNavigation().stop();
        MerchantSupplyData.get(MinecraftApi.server(c.npc())).journey(j.update(review?MerchantSupplyData.Stage.REVIEW:j.stage(),j.transfer(),j.personalRevision(),j.cargoRevision(),j.marketRevision(),j.time(),c.gameTime()+600,reason));
        c.npc().reportWorkActivity("保管钱货并等待",null,reason);return TaskTickResult.failed(reason);}
    @Override public void cancel(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){movement.remove(p.id());if(raw instanceof NpcEntityExecutionContext c)c.npc().getNavigation().stop();}
}
