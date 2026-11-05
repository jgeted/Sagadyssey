package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Two distinct duties: witnessed review, then lawful reserve/travel/carry/gift/receipt. */
public final class ReligionTask implements NpcTaskExecutor{
    public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:religion_alms"),PROVIDER=ResourceLocation.parse("sagadyssey:religion");
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    private static final Map<net.minecraft.server.MinecraftServer,Long> LAST=new WeakHashMap<>();
    public static boolean eligible(NpcBase n){return n.isAlive()&&NpcBrainRuntime.isPlanned(n)&&n.getCommand()==NpcCommand.WORK&&n.getTarget()==null
            &&!NpcWorkContext.from(n).hasEmployer()&&InstitutionData.get(n.getServer()).officesHeldBy(SubjectRef.character(n.getCharacterId()),n.level().getGameTime()).stream().anyMatch(o->o.authorities().contains(OfficeAuthority.REVIEW_ALMS)||o.authorities().contains(OfficeAuthority.MANAGE_ALMS));}
    public static void maintain(NpcBase n){var server=n.getServer();long now=n.level().getGameTime();if(now-LAST.getOrDefault(server,-100L)<100)return;LAST.put(server,now);
        for(var c:ReligionData.get(server).cases())if(!c.closed()&&(c.stage()==AlmsCase.Stage.RESERVED||c.stage()==AlmsCase.Stage.CARRYING))RightsStateData.get(server).renewReservations(c.token(),now,now+1200);}
    public static boolean canCarrySupply(NpcBase npc,SupplyFlowData.Route route){
        var t=ReligionData.get(npc.getServer()).temples().stream().filter(x->x.id().equals(route.principal().id())).findFirst().orElse(null);
        return t!=null&&route.destination().equals(t.alms().containerId())&&eligible(npc)&&ReligionService.authorized(npc,t.id(),OfficeAuthority.MANAGE_ALMS);
    }
    public static Optional<NpcPlan> choose(NpcBase n,long now){
        if(!eligible(n)||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();var d=ReligionData.get(n.getServer());
        var chosen=d.cases().stream().filter(c->!c.closed()&&c.retryAt()<=now&&(c.workerEntity()==null||c.workerEntity().equals(n.getUUID())&&c.workerCharacter().equals(n.getCharacterId())))
                .filter(c->d.temple(c.temple()).alms().dimension().equals(n.level().dimension().location().toString()))
                .filter(c->ReligionService.authorized(n,c.temple(),c.stage()==AlmsCase.Stage.REQUESTED?OfficeAuthority.REVIEW_ALMS:OfficeAuthority.MANAGE_ALMS))
                .sorted(Comparator.comparing((AlmsCase c)->c.stage()!=AlmsCase.Stage.CARRYING).thenComparingLong(AlmsCase::createdAt)).findFirst().orElse(null);
        if(chosen==null)return Optional.empty();chosen=chosen.claim(n.getUUID(),n.getCharacterId());d.aid(chosen);boolean review=chosen.stage()==AlmsCase.Stage.REQUESTED;
        var steps=new ArrayList<NpcPlanStep>();
        if(review){step(steps,chosen,"counter",NpcPlanPhase.TRAVELLING,now,true);step(steps,chosen,"review",NpcPlanPhase.ACTING,now,true);}
        else{if(chosen.stage()!=AlmsCase.Stage.CARRYING){step(steps,chosen,"reserve",NpcPlanPhase.RESERVING,now,false);step(steps,chosen,"source",NpcPlanPhase.TRAVELLING,now,false);step(steps,chosen,"pickup",NpcPlanPhase.ACTING,now,false);}
            step(steps,chosen,"counter",NpcPlanPhase.SETTLING,now,false);step(steps,chosen,"give",NpcPlanPhase.SETTLING,now,false);}
        step(steps,chosen,"finish",NpcPlanPhase.SETTLING,now,review);
        return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),chosen.token(),PROVIDER,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,steps,0,now,now,now+40,now+12000,null,review?"当面审核救济自述":"搬运并交付真实救济物资"));
    }
    private static void step(List<NpcPlanStep> steps,AlmsCase c,String op,NpcPlanPhase phase,long now,boolean review){steps.add(new NpcPlanStep(steps.size(),phase,ID,Map.of("case",c.id().toString(),"operation",op,"review",Boolean.toString(review)),NpcPlanStepStatus.PENDING,now,"地方教堂具体职责"));}
    @Override public ResourceLocation id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return tick(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext ctx))return TaskTickResult.failed("教堂职责需要真实实体");var n=ctx.npc();var server=n.getServer();long now=ctx.gameTime();var d=ReligionData.get(server);var c=d.aid(UUID.fromString(step.params().get("case")));var t=d.temple(c.temple());String op=step.params().get("operation");boolean review=Boolean.parseBoolean(step.params().get("review"));
        if(op.equals("finish")){n.reportWorkActivity("教堂事务收尾",null,"本案步骤已结束，转入下一职责或有限等候");return TaskTickResult.stepSucceeded("收尾完成");}
        if(review&&c.stage()!=AlmsCase.Stage.REQUESTED)return TaskTickResult.stepSucceeded("本案已有审查记录");
        if(c.stage()==AlmsCase.Stage.ISSUED)return TaskTickResult.stepSucceeded("同一救济已实际交付");if(c.closed())return TaskTickResult.failed(c.detail());
        if(!n.getUUID().equals(c.workerEntity())||!n.getCharacterId().equals(c.workerCharacter()))return TaskTickResult.failed("本案已由其他具体人物承办");
        if(c.pending()!=null){var receipt=TransferLedgerData.get(server).receipt(c.pending().transfer());
            if(receipt!=null){c=reconcile(n,c,receipt,now);if(!receipt.successful())return defer(n,plan,c,"库存、容量或权利发生变化，保留实物后复查",false);}
            else{d.aid(c.update(AlmsCase.Stage.REVIEW,c.reviewer(),now,now,c.pending(),"转移意图无可验证回执，人工核对，不重放",false));return TaskTickResult.failed("救济结算待核对");}}
        if(!eligible(n)||!ReligionService.authorized(n,t.id(),review?OfficeAuthority.REVIEW_ALMS:OfficeAuthority.MANAGE_ALMS))return defer(n,plan,c,"命令或有效职权改变，停止新增发放",false);
        if(c.stage()==AlmsCase.Stage.ISSUED)return TaskTickResult.stepSucceeded("已交付");
        if(op.equals("reserve")){
            if(c.stage()==AlmsCase.Stage.CARRYING||c.stage()==AlmsCase.Stage.RESERVED)return TaskTickResult.stepSucceeded("真实预留/携带义务仍在");
            if(n.getTransportSlotLimit()<1)return defer(n,plan,c,"执事当前没有可用真实货舱",false);
            int available=SupplyFlowService.available(server,t.alms(),SubjectRef.institution(t.id()),c.item(),now)-t.retained();
            if(available<c.quantity()){ReligionService.replenish(server,t,c,now);return defer(n,plan,c,"济贫库存不足或须保留章程份额，等待真实补给",true);}
            var rights=RightsStateData.get(server);var lease=new AssetReservation(SupplyFlowService.receipt(c.id(),"alms-source"),t.alms().containerId(),c.item(),c.quantity(),t.alms().owner(),c.token(),"经批准救济",now,now+1200);
            if(!rights.reserve(lease,SupplyFlowService.count(SupplyFlowService.container(server,t.alms()),c.item()),now))return defer(n,plan,c,"救济库存已被其他案件承诺",true);
            c=c.update(AlmsCase.Stage.RESERVED,c.reviewer(),now,now,null,"已预留机构真实食品，保留量未动",false);d.aid(c);return TaskTickResult.stepSucceeded("真实食品预留完成");
        }
        boolean source=op.equals("source")||op.equals("pickup");if(source&&c.stage()==AlmsCase.Stage.CARRYING)return TaskTickResult.stepSucceeded("取货回执已存在");
        var target=source?t.alms().position():t.counter();
        if(source&&(SupplyFlowService.container(server,t.alms())==null||!SupplyFlowService.validOwner(server,t.alms())))return defer(n,plan,c,"济贫设施损坏或所有权已变",false);
        if(!ReligionService.interacting(n,target)){
            if(!n.onGround())return TaskTickResult.running("安全落地后继续教堂路线");
            var route=FieldworkRoutes.plan(n,target,false);if(route==null)return defer(n,plan,c,"教堂职责路线不可达",false);
            var progress=movement.computeIfAbsent(plan.id(),id->{var v=new MovementProgress();v.reset(n,route.stand());return v;});
            if(progress.tick(n,route.stand())||(now%20==0||n.getNavigation().isDone())&&!FieldworkRoutes.move(n,route.stand()))return defer(n,plan,c,"教堂路线无法推进",false);
            n.reportWorkActivity(review?"前往救济接待窗口":source?"前往济贫仓取货":"携带救济前往领取者",target,"按本地有效职权执行具体案件");return TaskTickResult.running("沿真实路径移动");
        }
        n.getNavigation().stop();movement.remove(plan.id());
        if(op.equals("source")||op.equals("counter"))return TaskTickResult.stepSucceeded("实际到场");
        var applicant=ReligionService.body(server,c.recipient());
        if(review){if(applicant==null||!ReligionService.near(applicant,t)||n.distanceToSqr(applicant)>36||!n.hasLineOfSight(applicant))return defer(n,plan,c,"申请人尚未到场，不能以后台全知代替审查",false);ReligionService.review(n,c);n.playAction(NpcAction.TRADE_PRESENT,20);return TaskTickResult.stepSucceeded("当面自述审查已记录");}
        boolean pickup=op.equals("pickup");
        if(!pickup&&(applicant==null||!ReligionService.near(applicant,t)||n.distanceToSqr(applicant)>20.25||!n.hasLineOfSight(applicant)))return defer(n,plan,c,"领取者离开，保管原食品并等待",false);
        var rights=RightsStateData.get(server);UUID cargo=ContainerIds.npcCargo(n.getCharacterId());var carrier=SubjectRef.character(n.getCharacterId());rights.ensureContext(cargo,"npc:cargo",carrier,carrier,AccessPolicy.PRIVATE);
        if(!rights.context(cargo).custodian().equals(carrier))return defer(n,plan,c,"真实货舱保管身份已变，不覆盖既有权属",false);
        UUID from=pickup?t.alms().containerId():cargo,to=pickup?cargo:ReligionService.personalContainer(applicant);
        if(!pickup){var owner=SubjectRef.character(c.recipient());rights.ensureContext(to,"character:personal",owner,owner,AccessPolicy.PRIVATE);
            if(!rights.context(to).defaultBeneficialOwner().equals(owner))return defer(n,plan,c,"目标私人物品栏权属已变，保留食品等待合法交接",false);}
        if(pickup&&rights.reservationsForToken(c.token(),now).stream().filter(x->x.containerId().equals(from)).mapToInt(AssetReservation::quantity).sum()<c.quantity())return defer(n,plan,c,"取货前真实预留已失效",true);
        if(c.pending()==null){var pending=new AlmsCase.Pending(UUID.randomUUID(),to,pickup,rights.context(from).revision(),rights.context(to).revision(),now);c=c.update(c.stage(),c.reviewer(),now,now,pending,"固定救济转移意图已保存",false);d.aid(c);}
        var pending=c.pending();var request=new TransferRequest(pending.transfer(),carrier,pickup?TransferIntent.ISSUE:TransferIntent.GIFT,List.of(new TransferLeg(from,to,c.item(),c.quantity())),List.of(new ExpectedRevision(from,pending.sourceRevision()),new ExpectedRevision(to,pending.targetRevision())),c.token(),pending.time(),Map.of(from,SubjectRef.institution(t.id())));
        var bag=new LimitedContainerView(n.getTransportInventory(),n.getTransportSlotLimit());var sourceBox=pickup?SupplyFlowService.container(server,t.alms()):bag;var targetBox=pickup?bag:ReligionService.inventory(applicant);
        var receipt=new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(from,new TransferEndpoint(from,sourceBox),to,new TransferEndpoint(to,targetBox)),now);c=reconcile(n,c,receipt,now);
        if(!receipt.successful())return defer(n,plan,c,"交接未提交，食品未丢失，稍后重新核验",false);n.playAction(NpcAction.TRADE_PRESENT,20);return TaskTickResult.stepSucceeded(pickup?"机构食品已在真实货舱":"食品产权已合法赠与领取者");
    }
    private AlmsCase reconcile(NpcBase n,AlmsCase c,TransferReceipt receipt,long now){
        var p=c.pending();if(!receipt.transferId().equals(p.transfer()))throw new IllegalStateException("wrong alms receipt");var server=n.getServer();var rights=RightsStateData.get(server);
        if(receipt.successful()){
            ReligionData.get(server).receipt(c.id(),p.pickup(),receipt.transferId());
            if(p.pickup()){var owner=SubjectRef.institution(c.temple());if(!rights.reserve(new AssetReservation(SupplyFlowService.receipt(c.id(),"alms-cargo"),p.destination(),c.item(),c.quantity(),owner,c.token(),"机构食品在途保管",now,now+1200),SupplyFlowService.count(n.getTransportInventory(),c.item()),now))throw new IllegalStateException("alms cargo commitment conflict");
                c=c.update(AlmsCase.Stage.CARRYING,c.reviewer(),now,now,null,"已实际取货，机构产权随货保留",false);
            }else{rights.releaseReservations(c.token());c=c.update(AlmsCase.Stage.ISSUED,c.reviewer(),now,now,null,"真实赠与已完成，回执="+receipt.transferId(),false);}
        }else c=c.update(c.stage(),c.reviewer(),now,now,null,receipt.reason(),false);ReligionData.get(server).aid(c);
        if(receipt.successful()&&!p.pickup())ReligionService.issuedEvidence(server,n,c,now);return c;
    }
    private TaskTickResult defer(NpcBase n,NpcPlan plan,AlmsCase c,String reason,boolean stock){
        movement.remove(plan.id());n.getNavigation().stop();long now=n.level().getGameTime();
        if(!c.closed()){
            if(c.stage()==AlmsCase.Stage.CARRYING||c.pending()!=null)ReligionData.get(n.getServer()).aid(c.update(c.stage(),c.reviewer(),now,now+600,c.pending(),reason,false));
            else{RightsStateData.get(n.getServer()).releaseReservations(c.token());var next=c.attempts()>=15?AlmsCase.Stage.CANCELLED:c.stage()==AlmsCase.Stage.REQUESTED?AlmsCase.Stage.REQUESTED:stock?AlmsCase.Stage.WAITING_STOCK:AlmsCase.Stage.APPROVED;ReligionData.get(n.getServer()).aid(c.waitFor(next,now,reason));}
        }
        n.reportWorkActivity("教堂事务有限等候",null,reason);return TaskTickResult.failed(reason);
    }
    @Override public void cancel(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){if(raw instanceof NpcEntityExecutionContext c){var value=ReligionData.get(c.npc().getServer()).aid(UUID.fromString(s.params().get("case")));defer(c.npc(),p,value,"明确暂停/抢占；已携带食品继续保管",false);}}
    public static void onDeath(NpcBase n){long now=n.level().getGameTime();var d=ReligionData.get(n.getServer());for(var c:d.cases())if(!c.closed()&&n.getUUID().equals(c.workerEntity())){
        RightsStateData.get(n.getServer()).releaseReservations(c.token());
        if(c.stage()==AlmsCase.Stage.CARRYING||c.pending()!=null)d.aid(c.update(AlmsCase.Stage.REVIEW,c.reviewer(),now,now,c.pending(),"承办者死亡，真实掉落待核对，不登记已发放",false));
        else d.aid(c.waitFor(c.stage()==AlmsCase.Stage.REQUESTED?AlmsCase.Stage.REQUESTED:AlmsCase.Stage.APPROVED,now,"承办者死亡，未取货预留释放，可另派有效在职者"));
    }}
}
