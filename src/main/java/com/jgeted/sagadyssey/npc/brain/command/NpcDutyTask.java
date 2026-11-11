package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.brain.reservation.*;
import com.jgeted.sagadyssey.npc.command.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static com.jgeted.sagadyssey.npc.brain.command.NpcDutyData.*;

/** Medical consent and convoy protection share the sole planned movement controller. */
public final class NpcDutyTask implements NpcTaskExecutor {
    public static final Identifier ID=Identifier.parse("sagadyssey:duty_step"),PROVIDER=Identifier.parse("sagadyssey:finite_duty");
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    @Override public void forget(UUID body,UUID plan){movement.remove(plan);}
    @Override public Identifier id(){return ID;}
    public static boolean eligible(NpcBase npc){return npc.isAlive()&&NpcBrainRuntime.isPlanned(npc)
            &&(npc.getProfession()==NpcProfession.MEDIC||guard(npc)||NpcDutyData.get(MinecraftApi.server(npc)).active(npc.getUUID())!=null);}
    public static boolean withdrawing(NpcBase npc){if(npc.level().isClientSide()||MinecraftApi.server(npc)==null)return false;
        var d=NpcDutyData.get(MinecraftApi.server(npc)).active(npc.getUUID());return d!=null&&d.kind()==Kind.RETREAT&&npc.level().getGameTime()<d.expires();}
    /** Permission-bearing emergency request: safe withdrawal only, never pursuit/attack permission. */
    public static boolean requestWithdrawal(ServerPlayer issuer,NpcBase npc){
        if(!npc.isAlive()||npc.level()!=issuer.level()||!NpcBrainRuntime.isPlanned(npc))return false;
        long now=npc.level().getGameTime();var principal=CharacterRegistry.currentId(issuer);if(!issuer.isAlive()||principal==null)return false;
        var proofs=ServiceRelationshipData.get(MinecraftApi.server(npc)).activeForAgent(npc.getCharacterId(),now).stream()
                .filter(r->r.principalId().equals(principal)&&r.scopes().contains(AuthorityScope.FOLLOW)&&within(npc,r)).map(ServiceRelationship::id).limit(16).toList();
        if(proofs.isEmpty())return false;if(withdrawing(npc))return true;
        NpcBrainRuntime.cancel(npc,PlanCancellationReason.THREAT,"已核验原服务关系的有限紧急撤离；不改变工作义务或授予攻击权");
        var data=NpcDutyData.get(MinecraftApi.server(npc));var prior=data.active(npc.getUUID());if(prior!=null&&!prior.closed())data.put(prior.update(Status.CANCELLED,prior.worked(),now,"授权撤离抢占旧职责"));
        var d=new Duty(UUID.randomUUID(),npc.getUUID(),npc.getCharacterId(),npc.getUUID(),npc.getCharacterId(),Kind.RETREAT,principal,proofs,npc.level().dimension().identifier().toString(),issuer.blockPosition(),100,now,now+600,0,now,Status.OPEN,"原发令人物与服务证明的有限撤离，仍有期限");
        data.put(d);npc.clearOwnerCombatTargets();npc.setTarget(null);NpcCombatRuntime.retreat(npc,d.id());return true;
    }
    /** Autonomous self-preservation; not a new issuer, attack authorization or medical treatment. */
    public static void emergency(NpcBase npc){
        if(!npc.isAlive()||!NpcBrainRuntime.isPlanned(npc)||withdrawing(npc)||npc.getTarget()==null||!npc.getTarget().isAlive())return;
        var last=NpcDutyData.get(MinecraftApi.server(npc)).last(npc.getUUID(),Kind.RETREAT);long now=npc.level().getGameTime();
        if(last!=null&&now<last.lastTick()+400)return;
        var triage=InjuryData.get(MinecraftApi.server(npc)).triage(npc.getCharacterId(),now);
        if(npc.getHealth()>npc.getMaxHealth()*.25F&&!triage.incapacitated())return;
        var threat=npc.getTarget();var away=npc.position().subtract(threat.position());if(away.lengthSqr()<.01)away=new Vec3(1,0,0);
        var target=BlockPos.containing(npc.position().add(away.normalize().scale(12)));var owner=npc.getActiveOwner();
        if(owner!=null&&owner.isAlive()&&npc.distanceToSqr(owner)<=256)target=owner.blockPosition();
        if(!FieldworkRoutes.walkable(npc,target)) {
            var nearby=BlockPos.containing(npc.position().add(away.normalize().scale(6)));
            target=FieldworkRoutes.walkable(npc,nearby)?nearby:npc.blockPosition();
        }
        NpcBrainRuntime.cancel(npc,PlanCancellationReason.THREAT,"严重实际伤势，有限撤离；停止攻击、释放工作预留");
        var data=NpcDutyData.get(MinecraftApi.server(npc));var prior=data.active(npc.getUUID());if(prior!=null&&!prior.closed())data.put(prior.update(Status.CANCELLED,prior.worked(),now,"自主撤离优先，原职责有限取消"));
        var d=new Duty(UUID.randomUUID(),npc.getUUID(),npc.getCharacterId(),npc.getUUID(),npc.getCharacterId(),Kind.RETREAT,threat.getUUID(),List.of(),npc.level().dimension().identifier().toString(),target,100,now,now+300,0,now,Status.OPEN,"自主撤离，绝不自动回血");
        data.put(d);npc.clearOwnerCombatTargets();npc.setTarget(null);NpcCombatRuntime.retreat(npc,d.id());
    }
    public static Optional<NpcPlan> choose(NpcBase npc,long now){
        if(!eligible(npc)||npc.getTarget()!=null)return Optional.empty();var data=NpcDutyData.get(MinecraftApi.server(npc));var duty=data.active(npc.getUUID());
        if(duty!=null&&now>=duty.expires()){RightsStateData.get(MinecraftApi.server(npc)).releaseReservations(duty.token());data.put(duty.update(Status.CANCELLED,duty.worked(),now,"原职责到期，不无限延长"));duty=null;}
        if(duty==null){if(now<data.retryAt(npc.getUUID()))return Optional.empty();data.defer(npc.getUUID(),now);
            duty=npc.getProfession()==NpcProfession.MEDIC?medical(npc,now):escort(npc,now);
            if(duty==null&&guard(npc))duty=patrol(npc,now);if(duty==null)return Optional.empty();data.put(duty);}
        var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("duty",duty.id().toString()),NpcPlanStepStatus.PENDING,now,"重新核验原对象与凭据");
        return Optional.of(new NpcPlan(UUID.randomUUID(),npc.getUUID(),"duty:"+duty.id(),PROVIDER,NpcPlanKind.COMMITMENT,
                duty.kind()==Kind.RETREAT?NpcPlanPriority.CRITICAL:duty.kind()==Kind.MEDICAL?NpcPlanPriority.HIGH:NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now,
                duty.expires(),null,"有限职责；真实同意、材料与到场回执"));
    }
    private static Duty medical(NpcBase healer,long now){
        if(healer.getCommand()!=NpcCommand.WORK||!healer.hasBagItem(TreatmentType.BANDAGE.supply(),1))return null;
        var injuries=InjuryData.get(MinecraftApi.server(healer));
        var patients=healer.level().getEntitiesOfClass(NpcBase.class,healer.getBoundingBox().inflate(16),
                n->n.isAlive()&&!n.isSleeping()&&n.getTarget()==null&&healer.hasLineOfSight(n)&&(n==healer||healer.isCombatAlly(n)));
        patients.sort(Comparator.comparingDouble(healer::distanceToSqr));
        for(var patient:patients){
            var wound=injuries.woundsOf(patient.getCharacterId(),now).stream().filter(w->w.status().active()&&w.bleeding()>20)
                    .max(Comparator.comparingInt(WoundRecord::bleeding)).orElse(null);if(wound==null)continue;
            if(NpcLifeData.get(MinecraftApi.server(healer)).record(patient.getCharacterId())==null)continue;
            // The patient decides for themselves. Allied status alone is not medical consent.
            if(patient!=healer&&!NpcAgencyDecision.choose(patient,NpcAgencyDecision.Action.CARE,healer.getCharacterId(),80,0,false))continue;
            var accepted=new Duty(UUID.randomUUID(),healer.getUUID(),healer.getCharacterId(),patient.getUUID(),patient.getCharacterId(),Kind.MEDICAL,
                    wound.id(),List.of(),healer.level().dimension().identifier().toString(),patient.blockPosition(),28,now,now+1200,0,now,Status.OPEN,"本人接受当面处理这一个已观察伤口；非私人档案开放");
            NpcDutyEvidenceData.get(MinecraftApi.server(healer)).put(new NpcDutyEvidenceData.Evidence(accepted.id(),Kind.MEDICAL,wound.id(),patient.getCharacterId(),healer.getCharacterId(),patient.getCharacterId(),accepted.expires(),false));
            return accepted;
        }return null;
    }
    private static Duty escort(NpcBase guard,long now){
        if(!(guard.getActiveOwner() instanceof ServerPlayer owner)||guard.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(guard))return null;
        var groups=CommandGroupAttachments.get(owner);groups.registerMember(guard.getCharacterId(),guard.getNpcName());
        if(groups.strategyOf(guard.getCharacterId())!=SquadStrategy.ESCORT_CONVOY)return null;
        var principal=CharacterRegistry.currentId(owner);if(!owner.isAlive()||principal==null)return null;var relationships=ServiceRelationshipData.get(MinecraftApi.server(guard));
        var proofs=relationships.activeForAgent(guard.getCharacterId(),now).stream().filter(r->r.principalId().equals(principal)
                &&r.scopes().contains(AuthorityScope.LOGISTICS)&&within(guard,r)).map(ServiceRelationship::id).limit(16).toList();if(proofs.isEmpty())return null;
        var carrier=guard.level().getEntitiesOfClass(NpcBase.class,guard.getBoundingBox().inflate(16),n->n!=guard&&n.isAlive()
                &&guard.hasLineOfSight(n)&&n.isOwnedBy(owner.getUUID())
                &&(n.getProfession()==NpcProfession.COURIER||n.getProfession()==NpcProfession.TRADER)).stream()
                .filter(n->{groups.registerMember(n.getCharacterId(),n.getNpcName());return groups.squadOf(n.getCharacterId()).equals(groups.squadOf(guard.getCharacterId()));})
                .min(Comparator.comparingDouble(guard::distanceToSqr)).orElse(null);if(carrier==null)return null;
        var route=com.jgeted.sagadyssey.social.needs.SupplyFlowData.get(MinecraftApi.server(guard)).routes().stream()
                .filter(r->!r.closed()&&carrier.getUUID().equals(r.carrierEntity())&&carrier.getCharacterId().equals(r.carrierCharacter())
                        &&r.principal().equals(SubjectRef.character(principal))).findFirst().orElse(null);if(route==null)return null;
        var accepted=new Duty(UUID.randomUUID(),guard.getUUID(),guard.getCharacterId(),carrier.getUUID(),carrier.getCharacterId(),Kind.ESCORT,
                principal,proofs,guard.level().dimension().identifier().toString(),carrier.blockPosition(),100,now,now+600,0,now,Status.OPEN,"仅保护当前实际携货对象，不授权主动攻击、传送或远程交付");
        NpcDutyEvidenceData.get(MinecraftApi.server(guard)).put(new NpcDutyEvidenceData.Evidence(accepted.id(),Kind.ESCORT,route.id(),principal,guard.getCharacterId(),carrier.getCharacterId(),accepted.expires(),false));
        return accepted;
    }
    private static boolean guard(NpcBase n){return n.getProfession()==NpcProfession.WARRIOR||n.getProfession()==NpcProfession.ARCHER||n.getProfession()==NpcProfession.HEAVY;}
    private static Duty patrol(NpcBase npc,long now){
        if(npc.getCommand()!=NpcCommand.PATROL||npc.getPatrolCenter()==null||!NpcSchedulePlanner.allowsPatrol(npc))return null;
        var context=com.jgeted.sagadyssey.social.work.NpcWorkContext.from(npc);
        if(context.authority()!=NpcWorkAuthority.INSTITUTION||context.workplaceSettlementId()==null)return null;
        var previous=NpcDutyData.get(MinecraftApi.server(npc)).last(npc.getUUID(),Kind.PATROL);int leg=previous==null?0:previous.worked()%4;
        int radius=Math.max(4,Math.min(48,npc.getPatrolRadius()));int[][] offsets={{radius,0},{0,radius},{-radius,0},{0,-radius}};
        var point=npc.getPatrolCenter().offset(offsets[leg][0],0,offsets[leg][1]);if(!FieldworkRoutes.walkable(npc,point))return null;
        return new Duty(UUID.randomUUID(),npc.getUUID(),npc.getCharacterId(),npc.getUUID(),npc.getCharacterId(),Kind.PATROL,context.workplaceSettlementId(),List.of(),
                npc.level().dimension().identifier().toString(),point,leg+1,now,now+600,leg,now,Status.OPEN,"原机构岗位在已知巡逻点有限值守；未伪造玩家发令者");
    }
    private static boolean within(NpcBase n,ServiceRelationship r){var limits=r.limits();return(limits.dimension()==null||limits.dimension().isBlank()||limits.dimension().equals(n.level().dimension().identifier().toString()))
            &&(limits.workplaceSettlementId()==null||limits.workplaceSettlementId().equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(n).workplaceSettlementId()));}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        var c=(NpcEntityExecutionContext)raw;var npc=c.npc();var data=NpcDutyData.get(MinecraftApi.server(npc));var duty=data.find(UUID.fromString(step.params().get("duty")));
        if(duty==null)return fail(c,plan,null,"职责检查点缺失");
        if(duty.status()==Status.COMPLETED){cleanup(c,plan,duty);return TaskTickResult.stepSucceeded("原职责结算回执已存在");}
        if(duty.closed()||c.gameTime()>=duty.expires()||!duty.body().equals(npc.getUUID())||!duty.character().equals(npc.getCharacterId())
                ||!duty.dimension().equals(npc.level().dimension().identifier().toString()))return fail(c,plan,duty,"职责、人物或原期限失效");
        var entity=c.level().getEntity(duty.targetBody());
        if(entity==null&&!c.level().isPositionEntityTicking(duty.anchor()))return TaskTickResult.blocked("原对象所在区块尚未完成实体加载，不伪造对象离场",new NpcPlanBlocker("DUTY_TARGET_UNLOADED","等待原对象区块加载，保留原身份及总期限",c.gameTime()+20,duty.targetBody().toString()));
        if(!(entity instanceof NpcBase target)||!target.isAlive()
                ||!target.getCharacterId().equals(duty.targetCharacter()))return fail(c,plan,duty,"原对象离场、死亡或换身；不替换人物");
        if(duty.kind()==Kind.RETREAT||duty.kind()==Kind.PATROL){
            if(duty.kind()==Kind.RETREAT&&!duty.proofs().isEmpty()){
                boolean allowed=ServiceRelationshipData.get(MinecraftApi.server(npc)).activeForAgent(npc.getCharacterId(),c.gameTime()).stream()
                        .anyMatch(r->duty.proofs().contains(r.id())&&r.principalId().equals(duty.source())&&r.scopes().contains(AuthorityScope.FOLLOW)&&within(npc,r));
                if(!allowed)return fail(c,plan,duty,"原紧急撤离授权已撤销");
            }
            if(duty.kind()==Kind.PATROL){var context=com.jgeted.sagadyssey.social.work.NpcWorkContext.from(npc);
                if(context.authority()!=NpcWorkAuthority.INSTITUTION||!duty.source().equals(context.workplaceSettlementId())||npc.getCommand()!=NpcCommand.PATROL)return fail(c,plan,duty,"原机构巡逻岗位失效");}
            if(!npc.level().hasChunkAt(duty.anchor()))return fail(c,plan,duty,"原职责地点未加载");
            if(npc.distanceToSqr(Vec3.atBottomCenterOf(duty.anchor()))>2.25){
                var progress=movement.computeIfAbsent(plan.id(),id->{var v=new MovementProgress();v.reset(npc,duty.anchor());return v;});
                if(progress.tick(npc,duty.anchor()))return fail(c,plan,duty,"有限巡逻/撤离路径受阻");
                if(npc.getNavigation().isDone()||c.gameTime()%20==0)FieldworkRoutes.move(npc,duty.anchor());return TaskTickResult.running("真实移动至巡逻/撤离位置");
            }
            var next=duty.tick(c.gameTime());data.put(next);npc.getNavigation().stop();
            if(next.worked()<next.duration())return TaskTickResult.running("有限安全观察；没有自动回血");
            data.put(next.update(Status.COMPLETED,next.worked(),c.gameTime(),"实际到达且有限观察结束"));cleanup(c,plan,duty);return TaskTickResult.stepSucceeded("有限巡逻/撤离完成");
        }
        if(duty.kind()==Kind.ESCORT){
            var owner=npc.getActiveOwner();if(!(owner instanceof ServerPlayer player)||!duty.source().equals(CharacterRegistry.currentId(player))
                    ||CommandGroupAttachments.get(player).strategyOf(npc.getCharacterId())!=SquadStrategy.ESCORT_CONVOY||!target.isOwnedBy(player.getUUID())
                    ||!CommandGroupAttachments.get(player).squadOf(npc.getCharacterId()).equals(CommandGroupAttachments.get(player).squadOf(target.getCharacterId()))
                    ||npc.getCommand()!=NpcCommand.WORK)return fail(c,plan,duty,"原护送人、策略或对象关系失效");
            var relations=ServiceRelationshipData.get(MinecraftApi.server(npc));boolean allowed=relations.activeForAgent(npc.getCharacterId(),c.gameTime()).stream()
                    .anyMatch(r->duty.proofs().contains(r.id())&&r.principalId().equals(duty.source())&&r.scopes().contains(AuthorityScope.LOGISTICS)&&within(npc,r));
            if(!allowed)return fail(c,plan,duty,"原护送授权已撤销，不能借新合同复活");
            var evidence=NpcDutyEvidenceData.get(MinecraftApi.server(npc));if(!evidence.accepts(duty,c.gameTime()))return fail(c,plan,duty,"原护送任务证明缺失或撤销");
            var route=com.jgeted.sagadyssey.social.needs.SupplyFlowData.get(MinecraftApi.server(npc)).routeOrNull(evidence.find(duty.id()).record());
            if(route==null||!duty.targetBody().equals(route.carrierEntity())||!duty.targetCharacter().equals(route.carrierCharacter()))return fail(c,plan,duty,"原运输任务失效或更换携带人，不能替换护送对象");
            // The display marker can be cleared during a full-container retry while the
            // original physical cargo remains in custody. Only the shipment receipt ends protection.
            if(route.closed()){data.put(duty.update(Status.COMPLETED,duty.worked(),c.gameTime(),"原对象结束当前运输段，释放护送移动控制"));cleanup(c,plan,duty);return TaskTickResult.stepSucceeded("护送对象已停止运输");}
        }else if(npc.getProfession()!=NpcProfession.MEDIC||npc.getCommand()!=NpcCommand.WORK||target.getTarget()!=null||target.isSleeping())return fail(c,plan,duty,"医疗同意窗口或医师职责不再成立");
        if(duty.kind()==Kind.MEDICAL&&!NpcDutyEvidenceData.get(MinecraftApi.server(npc)).accepts(duty,c.gameTime()))return fail(c,plan,duty,"原患者同意已撤回或过期，不消费绷带");
        var targetPos=target.blockPosition();if(!npc.level().hasChunkAt(targetPos)||npc.distanceToSqr(target)>96*96)return fail(c,plan,duty,"保护对象离开可执行范围");
        double reach=duty.kind()==Kind.MEDICAL?6.25:16;
        if(npc.distanceToSqr(target)>reach||!npc.hasLineOfSight(target)){
            var progress=movement.computeIfAbsent(plan.id(),id->{var v=new MovementProgress();v.reset(npc,targetPos);return v;});
            if(progress.tick(npc,targetPos))return fail(c,plan,duty,"路径持续受阻，有限退避后重新选择");
            if(npc.getNavigation().isDone()||c.gameTime()%20==0)FieldworkRoutes.move(npc,targetPos);
            npc.reportWorkActivity(duty.kind()==Kind.MEDICAL?"前往已同意的患者":"护送真实运输对象",targetPos,"不传送，不远程作业");return TaskTickResult.running("前往原职责对象");
        }
        npc.getNavigation().stop();movement.remove(plan.id());npc.getLookControl().setLookAt(target,20,20);
        if(duty.kind()==Kind.MEDICAL){
            var injuries=InjuryData.get(MinecraftApi.server(npc));var wound=injuries.wound(duty.source());
            if(wound==null||!wound.patientCharacterId().equals(target.getCharacterId()))return fail(c,plan,duty,"原伤口身份无效");
            var existing=injuries.treatment(duty.id());
            if(duty.status()==Status.SETTLING&&existing==null){data.put(duty.update(Status.REVIEW,duty.worked(),c.gameTime(),"医疗结算中断；核对原库存与伤口，禁止再次消费"));cleanup(c,plan,duty);return TaskTickResult.failed("医疗结算需要核对");}
            if(existing!=null){data.put(duty.update(Status.COMPLETED,duty.worked(),c.gameTime(),"原医疗物资消费与伤口回执已存在"));cleanup(c,plan,duty);return TaskTickResult.stepSucceeded("同一伤口治疗不重复消费");}
            if(!wound.status().active()||wound.bleeding()<=20)return fail(c,plan,duty,"原伤口已稳定，不重复包扎");
            var key=new NpcReservationKey("patient",duty.dimension()+":"+duty.source());
            if(!NpcBrainRuntime.reservations(MinecraftApi.server(npc)).acquire(key,plan.id(),npc.getUUID(),c.gameTime(),duty.expires()).acquired())
                return TaskTickResult.blocked("另一个医师正在处理同一伤口",new NpcPlanBlocker("PATIENT_BUSY","同一伤口只能有一位医师作业",c.gameTime()+20,duty.source().toString()));
            var rights=RightsStateData.get(MinecraftApi.server(npc));var bag=ContainerIds.npcPersonal(npc.getCharacterId());var healer=SubjectRef.character(npc.getCharacterId());
            rights.ensureContext(bag,"npc:"+npc.getCharacterId()+":personal",healer,healer,AccessPolicy.PRIVATE);
            var fingerprint=ItemFingerprint.of(new ItemStack(TreatmentType.BANDAGE.supply()));
            int physical=npc.getPersonalInventory().count(stack->stack.is(TreatmentType.BANDAGE.supply()));
            if(!rights.reserve(new AssetReservation(UUID.nameUUIDFromBytes(duty.token().getBytes(java.nio.charset.StandardCharsets.UTF_8)),bag,fingerprint,1,healer,duty.token(),"本人同意的伤口治疗",c.gameTime(),duty.expires()),physical,c.gameTime()))return fail(c,plan,duty,"真实绷带不足或已经预留");
        }
        var progressed=duty.tick(c.gameTime());data.put(progressed);npc.reportWorkActivity(duty.kind()==Kind.MEDICAL?"当面包扎":"有限护送警戒",targetPos,"按实际在场执行次数计时");
        if(progressed.worked()<progressed.duration())return TaskTickResult.running("有限职责进行中");
        if(duty.kind()==Kind.MEDICAL){
            data.put(progressed.update(Status.SETTLING,progressed.worked(),c.gameTime(),"固定治疗意图即将提交"));
            try{MedicalService.treat(InjuryData.get(MinecraftApi.server(npc)),RightsStateData.get(MinecraftApi.server(npc)),duty.id(),duty.source(),
                    SubjectRef.character(npc.getCharacterId()),ContainerIds.npcPersonal(npc.getCharacterId()),npc.getPersonalInventory(),TreatmentType.BANDAGE,c.gameTime());}
            catch(RuntimeException exception){return fail(c,plan,data.find(duty.id()),"医疗不能提交："+exception.getMessage());}
            npc.playAction(NpcAction.MEDIC_TEND,28);
            // Treat the actual wound; no arbitrary HP injection and no repeated experience grant on recovery.
            NpcLifeData.get(MinecraftApi.server(npc)).remember(target.getCharacterId(),new NpcMemoryRecord("medical_treatment",npc.getCharacterId(),"本人同意的当面包扎；来源治疗="+duty.id(),4,20,c.gameTime(),c.gameTime()+24000),c.gameTime());
        }
        data.put(data.find(duty.id()).update(Status.COMPLETED,progressed.worked(),c.gameTime(),"真实职责完成，重新选择下一任务"));cleanup(c,plan,duty);return TaskTickResult.stepSucceeded("真实职责完成");
    }
    private TaskTickResult fail(NpcEntityExecutionContext c,NpcPlan p,Duty d,String detail){if(d!=null&&!d.closed())NpcDutyData.get(MinecraftApi.server(c.npc())).put(d.update(Status.CANCELLED,d.worked(),c.gameTime(),detail));
        cleanup(c,p,d);NpcDutyData.get(MinecraftApi.server(c.npc())).defer(c.npc().getUUID(),c.gameTime());return TaskTickResult.failed(detail);}
    private void cleanup(NpcEntityExecutionContext c,NpcPlan p,Duty d){movement.remove(p.id());c.npc().getNavigation().stop();NpcBrainRuntime.reservations(MinecraftApi.server(c.npc())).releasePlan(p.id());if(d!=null)RightsStateData.get(MinecraftApi.server(c.npc())).releaseReservations(d.token());}
    @Override public void cancel(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){var c=(NpcEntityExecutionContext)raw;var d=NpcDutyData.get(MinecraftApi.server(c.npc())).find(UUID.fromString(s.params().get("duty")));
        cleanup(c,p,d);if(d!=null&&!d.closed()&&reason!=PlanCancellationReason.THREAT&&reason!=PlanCancellationReason.URGENT_NEED)
            NpcDutyData.get(MinecraftApi.server(c.npc())).put(d.update(Status.CANCELLED,d.worked(),c.gameTime(),"职责取消："+reason));}
}
