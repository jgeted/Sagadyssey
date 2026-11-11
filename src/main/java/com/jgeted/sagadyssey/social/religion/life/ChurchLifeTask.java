package com.jgeted.sagadyssey.social.religion.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.religion.*;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** One plan executor, office-scoped host duties and consent-scoped attendee duties. */
public final class ChurchLifeTask implements NpcTaskExecutor {
    public static final Identifier ID=Identifier.parse("sagadyssey:church_life"),PROVIDER=ID;
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    public static boolean eligible(NpcBase n){if(!n.isAlive()||!NpcBrainRuntime.isPlanned(n)||n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null||NpcWorkContext.from(n).hasEmployer())return false;
        UUID person=n.getCharacterId();var s=MinecraftApi.server(n);long now=n.level().getGameTime();return InstitutionData.get(s).officesHeldBy(SubjectRef.character(person),now).stream().anyMatch(o->Arrays.stream(ChurchActivity.Kind.values()).anyMatch(k->o.authorities().contains(k.power)))
                ||ChurchLifeData.get(s).activities().stream().anyMatch(a->!a.stage().closed()&&a.consent().contains(person));}
    public static Optional<NpcPlan> choose(NpcBase n,long now){if(!eligible(n)||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();var d=ChurchLifeData.get(MinecraftApi.server(n));UUID person=n.getCharacterId();
        for(var a:d.activities().stream().sorted(Comparator.comparingLong(ChurchActivity::scheduled)).toList()){
            if(a.stage().closed()||a.scheduled()>now||a.retryAt()>now||a.deadline()<now&&a.stage()!=ChurchActivity.Stage.SETTLING)continue;var t=ReligionData.get(MinecraftApi.server(n)).temple(a.temple());if(!t.alms().dimension().equals(n.level().dimension().identifier().toString()))continue;
            boolean attendee=a.consent().contains(person)&&!person.equals(a.host());
            if(attendee&&a.host()==null)continue;
            boolean host=!attendee&&(a.host()==null||person.equals(a.host()))&&ReligionService.authorized(n,a.temple(),a.kind().power);
            if(!host&&!attendee||host&&!ChurchLifeService.venueFree(MinecraftApi.server(n),a))continue;
            if(host&&a.stage()==ChurchActivity.Stage.PLANNED){a=a.claim(person,now);d.put(a);}
            var steps=new ArrayList<NpcPlanStep>();add(steps,a,attendee,"move",NpcPlanPhase.TRAVELLING,now);add(steps,a,attendee,"act",NpcPlanPhase.ACTING,now);add(steps,a,attendee,"finish",NpcPlanPhase.SETTLING,now);
            return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),a.token(),PROVIDER,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,steps,0,now,now,now+40,a.deadline()+200,null,attendee?"明确同意后前往本地活动":"持有效职位执行教会生活职责"));
        }return Optional.empty();}
    private static void add(List<NpcPlanStep> steps,ChurchActivity a,boolean attendee,String op,NpcPlanPhase phase,long now){steps.add(new NpcPlanStep(steps.size(),phase,ID,Map.of("activity",a.id().toString(),"attendee",Boolean.toString(attendee),"op",op),NpcPlanStepStatus.PENDING,now,"教会生活具体计划"));}
    @Override public Identifier id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){movement.remove(p.id());return tick(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){if(!(raw instanceof NpcEntityExecutionContext ctx))return TaskTickResult.failed("需要真实人物实体");
        var n=ctx.npc();var s=MinecraftApi.server(n);var d=ChurchLifeData.get(s);var a=d.activity(UUID.fromString(step.params().get("activity")));var t=ReligionData.get(s).temple(a.temple());long now=ctx.gameTime();boolean attendee=Boolean.parseBoolean(step.params().get("attendee"));String op=step.params().get("op");
        if(a.stage().closed()){n.getNavigation().stop();movement.remove(plan.id());return TaskTickResult.stepSucceeded("本次活动已结束，恢复生活职责");}
        if(attendee&&!a.consent().contains(n.getCharacterId())){n.getNavigation().stop();movement.remove(plan.id());return TaskTickResult.stepSucceeded("本人已撤回同意，不继续参与");}
        if(op.equals("finish")){n.getNavigation().stop();movement.remove(plan.id());n.reportWorkActivity("教会活动收尾休整",null,"释放本次场所，返回下一职责或家庭生活");return TaskTickResult.stepSucceeded("完成本次活动收尾");}
        if(!eligible(n)||!attendee&&(!n.getCharacterId().equals(a.host())||!ReligionService.authorized(n,a.temple(),a.kind().power))){if(!attendee)ChurchLifeService.cancel(s,a,"主持者职权或工作状态失效；原料承诺释放");return TaskTickResult.failed("不代替已撤销职权或未经同意者行动");}
        if(a.stage()==ChurchActivity.Stage.SETTLING){if(now-a.updated()>=30&&!attendee)d.put(a.state(ChurchActivity.Stage.COMPLETED,now,"本次活动、文书与知识已经结算，场所释放"));return TaskTickResult.stepSucceeded("散会；具体结果已经登记");}
        if(a.retryAt()>now)return TaskTickResult.running(a.detail());
        boolean venue=ChurchLifeService.usable(s,t,a.venue());
        if(!venue&&a.kind()!=ChurchActivity.Kind.MAINTENANCE)return blocked(n,plan,a,attendee,"场所支撑或空间失效，不能虚构到场");
        BlockPos target=a.venue();
        if(a.kind()==ChurchActivity.Kind.COPY||a.kind().manuscript())target=t.treasury().position();
        else if(a.kind()==ChurchActivity.Kind.MAINTENANCE&&!venue)target=a.venue().east();
        if(attendee){int slot=a.invited().indexOf(n.getCharacterId());target=a.venue().offset(slot%2==0?2:-2,0,(slot/2)%3-1);}
        if((attendee||a.kind()==ChurchActivity.Kind.LESSON)&&n.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)>2.25){
            if(!ChurchLifeService.usable(s,t,target))return blocked(n,plan,a,true,"受邀站位不安全，不强行入座");
            var destination=target;var progress=movement.computeIfAbsent(plan.id(),id->{var v=new MovementProgress();v.reset(n,destination);return v;});
            if(progress.tick(n,target)||(now%20==0||n.getNavigation().isDone())&&!FieldworkRoutes.move(n,target))return blocked(n,plan,a,true,"参与者站位路线无法推进");
            n.reportWorkActivity("走向本人的集会站位",target,"明确同意后实际到场，不以远距离交互范围充当入座");return TaskTickResult.running("沿真实路径前往站位");
        }
        if(!attendee&&!ReligionService.interacting(n,target)){
            if(!n.onGround())return TaskTickResult.running("安全落地后继续路线");var route=FieldworkRoutes.plan(n,target,false);if(route==null)return blocked(n,plan,a,attendee,"本地活动路线不可达");
            var progress=movement.computeIfAbsent(plan.id(),id->{var v=new MovementProgress();v.reset(n,route.stand());return v;});
            if(progress.tick(n,route.stand())||(now%20==0||n.getNavigation().isDone())&&!FieldworkRoutes.move(n,route.stand()))return blocked(n,plan,a,attendee,"本地活动路径无法推进");
            n.reportWorkActivity(attendee?"前往已同意的集会":a.kind()==ChurchActivity.Kind.VISIT?"前往当面探访":"前往具体教堂职责",target,"沿真实路径到场，不传送履约");return TaskTickResult.running("实际移动");
        }
        n.getNavigation().stop();movement.remove(plan.id());if(op.equals("move"))return TaskTickResult.stepSucceeded("人物实际到达工作地点");
        if(attendee){n.reportWorkActivity("参与已同意的教堂活动",a.venue(),"等待实际主持者和共同参与者；不自行动用库房");return TaskTickResult.running("在场参加，结束后恢复原生活");}
        var actual=ChurchLifeService.attendees(s,a,n);
        if(actual.size()<a.minimum()||a.kind()==ChurchActivity.Kind.MARRIAGE&&!actual.containsAll(a.invited()))return blocked(n,plan,a,false,"已同意且实际到场人数不足，不能代签或远程举行");
        if(a.source()!=null){var source=d.record(a.source());if(!ChurchLifeService.canRead(s,source,n.getCharacterId())||!ChurchLifeService.physicalPresent(s,source)||!ReligionService.interacting(n,t.treasury().position()))return blocked(n,plan,a,false,"实际教材不在场、未经授权或教员离书过远");}
        if(a.kind()==ChurchActivity.Kind.FUNERAL){var beliefs=com.jgeted.sagadyssey.knowledge.KnowledgeStateData.get(s).beliefsAbout(n.getCharacterId(),a.subject());
            if(beliefs.stream().noneMatch(b->b.key().predicate().equals("church.death_report")))return blocked(n,plan,a,false,"主持者没有来源明确的死亡报告；不从后台死亡状态补知识");}
        try{
            if(a.kind().manuscript()){
                if(!SupplyFlowService.validOwner(s,t.treasury())||SupplyFlowService.container(s,t.treasury())==null)return blocked(n,plan,a,false,"抄写库房设施或权属失效");
                var r=ChurchLifeService.prepareRecord(s,a,actual);var batch=ProductionBatchData.get(s).batch(a.id());
                long started=now; // Fixed intent excludes time; renew against the current clock, not the old start.
                ProductionService.begin(s,a.id(),a.host(),SubjectRef.institution(a.temple()),t.treasury().containerId(),SupplyFlowService.container(s,t.treasury()),"sagadyssey:church_manuscript",List.of(new ProductionMaterial(new ItemFingerprint("minecraft:paper",0),3),new ProductionMaterial(new ItemFingerprint("minecraft:ink_sac",0),1),new ProductionMaterial(new ItemFingerprint("minecraft:leather",0),1)),new ProductionMaterial(r.physical(),1),a.duration(),started);
            }
            if(a.work()<a.duration()){a=a.progress(now);d.put(a);d.expose(a,actual);n.reportWorkActivity("教堂职责："+a.kind(),target,"具体在场作业，保存实际进度，不计算离线劳动");if(now%20==0)n.playAction(a.kind()==ChurchActivity.Kind.COPY?NpcAction.TRADE_PRESENT:NpcAction.HEAD_NOD,10);return TaskTickResult.running("具体作业 "+a.work()+"/"+a.duration());}
            var r=ChurchLifeService.prepareRecord(s,a,actual);
            if(a.kind().manuscript())ProductionService.complete(s,a.id(),SupplyFlowService.container(s,t.treasury()),a.work(),now,ChurchLifeService.book(r));
            if(a.kind()==ChurchActivity.Kind.MAINTENANCE)repair(n,a,t);
            a=a.state(ChurchActivity.Stage.SETTLING,now,"在场作业结算成功，文书有作者、来源与查阅边界");d.put(a);RightsStateData.get(s).releaseReservations(a.token());ChurchLifeService.publish(s,a,actual);return TaskTickResult.running("散会/收尾");
        }catch(ProductionBlockedException e){return blocked(n,plan,a,false,e.getMessage());}
    }
    private static void repair(NpcBase n,ChurchActivity a,TempleRecord t){var s=MinecraftApi.server(n);var level=n.level();BlockPos block=a.venue().below();var old=level.getBlockState(block);
        if(old.isCollisionShapeFullBlock(level,block))return;if(!old.isAir())throw new ProductionBlockedException("UNSAFE_SITE","指定位置不是可安全修补的空缺，不拆除用户方块");
        if(!SupplyFlowService.validOwner(s,t.treasury()))throw new ProductionBlockedException("RIGHTS_CHANGED","修补材料库不再归本地机构");var box=SupplyFlowService.container(s,t.treasury());if(box==null)throw new ProductionBlockedException("MISSING_STORE","修补材料库不在场");
        var rights=RightsStateData.get(s);var item=new ItemFingerprint("minecraft:cobblestone",0);var owner=SubjectRef.institution(t.id());var resolver=new RightsService(rights);int slot=-1;
        for(int i=0;i<box.getContainerSize();i++)if(item.matches(box.getItem(i))&&resolver.can(owner,AssetRight.CONSUME,t.treasury().containerId(),i,item,level.getGameTime()).status()==RightsVerdict.Status.ALLOW&&rights.claimsAt(t.treasury().containerId(),i,item).stream().allMatch(c->c.beneficialOwner().equals(owner))){slot=i;break;}
        if(slot<0)throw new ProductionBlockedException("MISSING_INPUT","需要真实、依法可消耗的圆石来补步道");
        int owned=0;for(int i=0;i<box.getContainerSize();i++)if(item.matches(box.getItem(i))&&resolver.can(owner,AssetRight.CONSUME,t.treasury().containerId(),i,item,level.getGameTime()).status()==RightsVerdict.Status.ALLOW&&rights.claimsAt(t.treasury().containerId(),i,item).stream().allMatch(c->c.beneficialOwner().equals(owner)))owned+=box.getItem(i).getCount();
        if(!rights.reserve(new AssetReservation(a.id(),t.treasury().containerId(),item,1,owner,a.token(),"补实物步道",level.getGameTime(),level.getGameTime()+1200),owned,level.getGameTime()))throw new ProductionBlockedException("RESERVED","修补材料已有其他承诺");
        var snap=rights.snapshotFor(Set.of(t.treasury().containerId()));ItemStack before=box.getItem(slot).copy();
        var claims=rights.claimsAt(t.treasury().containerId(),slot,item);if(claims.size()>1)throw new ProductionBlockedException("MIXED_TITLE","修补不合并多项产权主张，需先核对物资");
        try{box.getItem(slot).shrink(1);if(!level.setBlock(block,Blocks.COBBLESTONE.defaultBlockState(),3))throw new IllegalStateException("physical repair failed");
            rights.removeClaimsForSlot(t.treasury().containerId(),slot);if(!box.getItem(slot).isEmpty()&&!claims.isEmpty()){var c=claims.getFirst();rights.putClaim(new AssetClaim(c.claimId(),c.containerId(),slot,c.fingerprint(),box.getItem(slot).getCount(),c.beneficialOwner(),c.custodian(),c.encumbrances(),c.reservations(),c.provenance(),c.revision()+1));}
            rights.consumeReservation(a.token(),t.treasury().containerId(),item,1);rights.bumpContext(t.treasury().containerId());box.setChanged();
        }catch(RuntimeException x){box.setItem(slot,before);level.setBlock(block,old,3);rights.restore(snap);rights.releaseReservations(a.token());throw x;}}
    private TaskTickResult blocked(NpcBase n,NpcPlan p,ChurchActivity a,boolean attendee,String why){n.getNavigation().stop();movement.remove(p.id());if(!attendee){long now=n.level().getGameTime();if(a.attempts()>=11||now>=a.deadline())ChurchLifeService.cancel(MinecraftApi.server(n),a,"有限延期结束："+why);else ChurchLifeData.get(MinecraftApi.server(n)).put(a.defer(now,why));}
        n.reportWorkActivity("教会活动有限等候",null,why);return TaskTickResult.failed(why);}
    @Override public void cancel(NpcExecutionContext raw,NpcPlan p,NpcPlanStep step,PlanCancellationReason reason){movement.remove(p.id());if(raw instanceof NpcEntityExecutionContext c){c.npc().getNavigation().stop();var d=ChurchLifeData.get(MinecraftApi.server(c.npc()));var a=d.activity(UUID.fromString(step.params().get("activity")));if(!a.stage().closed()&&!a.stage().equals(ChurchActivity.Stage.SETTLING)&&c.npc().getCharacterId().equals(a.host()))d.put(a.defer(c.gameTime(),"计划暂停/抢占，保存原进度，重新核验同意与在场"));}}
    public static void onDeath(NpcBase n){for(var a:ChurchLifeData.get(MinecraftApi.server(n)).activities())if(!a.stage().closed()&&n.getCharacterId().equals(a.host()))ChurchLifeService.cancel(MinecraftApi.server(n),a,"主持者死亡：停止未完成活动并释放预留；已提交结果只补收尾");}
}
