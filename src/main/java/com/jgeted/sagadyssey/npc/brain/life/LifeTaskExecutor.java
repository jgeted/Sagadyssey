package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkCargo;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.LecternBlock;
import java.util.*;

public final class LifeTaskExecutor implements NpcTaskExecutor {
    private final Identifier id;
    private final Map<UUID, Motion> motions = new HashMap<>();
    @Override public void forget(UUID body,UUID plan){motions.remove(plan);}
    private record Motion(BlockPos stand, long started, long lastProgress, double distance) { }
    public LifeTaskExecutor(Identifier id) { this.id = id; }
    private static String label(String op) {
        return switch(op) { case "EAT" -> "进食";case "SLEEP" -> "私人床位休息";case "CARE" -> "当面家庭照料";
            case "HOME" -> "回家";case "TALK","VISIT" -> "当面交谈";case "READ" -> "阅读";case "TAVERN" -> "酒馆休闲";
            case "OBSERVE" -> "观察周围";default -> "短时等待"; };
    }
    @Override public Identifier id() { return id; }
    @Override public TaskTickResult start(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { return tick(c,p,s); }
    @Override public TaskTickResult recover(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) {
        motions.remove(p.id());
        if (c instanceof NpcEntityExecutionContext e) { e.npc().getNavigation().stop(); if (e.npc().isSleeping()) e.npc().stopSleeping();
            if(id.equals(LifePlanFactory.ACT)) LifeActivityData.get(e.level().getServer()).restart(p.id()+":"+s.index(),e.gameTime()); }
        return tick(c,p,s);
    }
    @Override public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        if (!(context instanceof NpcEntityExecutionContext e)) return TaskTickResult.failed("需要真实实体");
        NpcBase npc = e.npc(); ServerLevel level = e.level(); long now = e.gameTime();
        var data = LifeActivityData.get(level.getServer()); var params = step.params(); String op = params.get("operation");
        BlockPos target = LifePlanFactory.target(params);
        if (!level.dimension().identifier().toString().equals(params.get("dimension")) || !level.hasChunkAt(target))
            return fail(npc, data, plan, now, "已知生活地点尚未加载或维度改变");
        if (!authorized(npc, op, target, params)) return fail(npc,data,plan,now,"生活目标、设施或权限失效");
        String place = params.get("dimension") + ':' + target.asLong();
        int capacity = Integer.parseInt(params.getOrDefault("capacity", "1"));
        if (VisitCapacity.acquire(NpcBrainRuntime.reservations(level.getServer()), place, capacity,
                plan.id(), npc.getUUID(), now, plan.expiresAt()) == null)
            return fail(npc,data,plan,now,"生活地点容量已满，稍后另选活动");
        if (id.equals(LifePlanFactory.RESERVE)) return TaskTickResult.stepSucceeded("生活地点已预约");
        if (id.equals(LifePlanFactory.MOVE)) return move(npc,data,plan,target,now);
        if (npc.distanceToSqr(target.getX()+.5,target.getY()+.5,target.getZ()+.5) > 20.25
                || !FieldworkRoutes.closeEnough(npc,target) && !npc.isSleeping())
            return fail(npc,data,plan,now,"未到达真实活动地点");
        String key = plan.id() + ":" + step.index();
        String settlement = data.settlement(key);
        if ((op.equals("EAT") || op.equals("CARE")) && params.getOrDefault("source","BAG").equals("BAG")) {
            var work = FieldworkData.get(level.getServer()); var prior = work.outputConsumption(key).orElse(null);
            if (prior != null && (!prior.npcId().equals(npc.getUUID()) || !prior.personId().equals(npc.getCharacterId())
                    || !prior.containerId().equals(ContainerIds.npcPersonal(npc.getCharacterId()))))
                return fail(npc,data,plan,now,"原消费动作的人物或容器不一致，保留回执等待核对");
            String review = work.outputConsumptionReview(npc.getUUID(), data::settlement);
            if (!review.isEmpty()) return fail(npc,data,plan,now,review);
        }
        if ("DONE".equals(settlement)) return TaskTickResult.stepSucceeded("已提交生活回执，不重复结算");
        if ("PENDING".equals(settlement)) return fail(npc,data,plan,now,"生活结算中断，需要核对库存与需求");
        npc.getNavigation().stop(); npc.reportWorkActivity(label(op), target, "正在真实地点进行有限生活活动");
        if (op.equals("EAT") || op.equals("CARE")) return eat(e,plan,step,key,op,data);
        if (op.equals("TALK") || op.equals("VISIT")) return conversation(e,plan,step,key,data);
        long duration = Long.parseLong(params.get("duration"));
        long began = data.start(key,now);
        if (op.equals("SLEEP")) {
            if (!npc.isSleeping()) npc.startSleeping(target);
            var life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
            if (now - began < duration && NpcLifeBehavior.isRestWindow(life.shift(), level.getDayTime()))
                return TaskTickResult.running("在私人床位睡眠");
            npc.stopSleeping();
        } else if (now - began < duration) {
            if (now % 40 == 0) npc.getLookControl().setLookAt(target.getX()+.5,target.getY()+1,target.getZ()+.5);
            return TaskTickResult.running("有限生活活动进行中");
        }
        data.pending(key); data.done(key); data.defer(npc.getUUID(), now);
        resumeWorkScan(npc);
        motions.remove(plan.id()); return TaskTickResult.stepSucceeded("生活活动结束，重新选择下一计划");
    }

    private TaskTickResult move(NpcBase npc, LifeActivityData data, NpcPlan plan, BlockPos target, long now) {
        boolean precise=target.equals(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.idleHomeStand(npc))
                &&plan.currentStep().params().getOrDefault("operation","").equals("OBSERVE");
        if (npc.onGround() && npc.distanceToSqr(target.getX()+.5,target.getY()+(precise?0:.5),target.getZ()+.5) <= (precise?.16:20.25)
                && FieldworkRoutes.closeEnough(npc,target)) { npc.getNavigation().stop(); motions.remove(plan.id()); return TaskTickResult.stepSucceeded("已到达生活地点"); }
        if (!npc.onGround()) return TaskTickResult.running("等待安全落地");
        var motion = motions.get(plan.id());
        if (motion == null) {
            var route = precise?null:FieldworkRoutes.plan(npc,target,false);
            if (precise?!FieldworkRoutes.walkable(npc,target):route==null) return fail(npc,data,plan,now,"没有到生活地点的安全路线");
            var stand=precise?target:route.stand();
            motion = new Motion(stand,now,now,npc.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand)));
        }
        double distance = npc.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(motion.stand()));
        if (distance < motion.distance() - .1) motion = new Motion(motion.stand(),motion.started(),now,distance);
        motions.put(plan.id(),motion);
        if (now - motion.started() >= 2400 || now - motion.lastProgress() >= 120) return fail(npc,data,plan,now,"生活路线持续受阻，有限退避后另选");
        if (npc.getNavigation().isDone() || now % 20 == 0) FieldworkRoutes.move(npc,motion.stand());
        npc.reportWorkActivity("前往生活地点",target,"沿实际路径前往已知地点");
        return TaskTickResult.running("前往已知生活地点");
    }

    private TaskTickResult eat(NpcEntityExecutionContext e, NpcPlan plan, NpcPlanStep step, String key, String op, LifeActivityData data) {
        var npc = e.npc(); var level = e.level(); long now = e.gameTime(); var actor = SubjectRef.character(npc.getCharacterId());
        Container source = npc.getPersonalInventory(); UUID containerId = ContainerIds.npcPersonal(npc.getCharacterId());
        SubjectRef consumingAs = actor; AccessPolicy policy = AccessPolicy.PRIVATE;
        var target = LifePlanFactory.target(step.params());
        if (!step.params().getOrDefault("source","BAG").equals("BAG")) {
            if(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.protects(level.getServer(),level.dimension().identifier().toString(),target))return fail(npc,data,plan,now,"仓储尚未完成权威交接，保留物资");
            source = WarehouseContainers.asContainer(level.getBlockEntity(target));
            String type = step.params().get("source").equals("HOUSEHOLD") ? "household_storage" : "private_storage";
            containerId = ContainerIds.block(type,step.params().get("dimension"),target);
            if (type.equals("household_storage")) {
                var household = HouseholdData.get(level.getServer()).forMember(npc.getCharacterId());
                if (household == null || !target.equals(household.sharedStorage())) return fail(npc,data,plan,now,"家庭取食权限撤销");
                consumingAs = SubjectRef.household(household.id()); policy = AccessPolicy.HOUSEHOLD;
            }
        }
        NpcBase recipient = npc;
        if (op.equals("CARE")) {
            recipient = peer(level,UUID.fromString(step.params().get("peer")));
            if (recipient == null || npc.distanceToSqr(recipient) > 9 || !npc.hasLineOfSight(recipient)) return fail(npc,data,plan,now,"受照料者未实际到场");
        }
        var life = NpcLifeData.get(level.getServer());
        var needs = life.ensureNeeds(recipient.getCharacterId(),now);
        if (needs.hunger() < 350) return TaskTickResult.stepSucceeded("需求已由其他来源满足，不重复消费");
        if (source == null) return fail(npc,data,plan,now,"食物容器消失");
        var rights = RightsStateData.get(level.getServer());
        rights.ensureContext(containerId, "life:" + containerId, consumingAs,consumingAs,policy);
        if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc)!=null
                &&com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodSlot(npc,source,step.params().getOrDefault("source","BAG"),target,true,now)<0)
            return fail(npc,data,plan,now,"合法食物余量不足；种用和已有承诺保持占用");
        int slot = -1;
        for (int i=0;i<source.getContainerSize();i++) {
            var stack=source.getItem(i);
            if (!NpcFoodItems.canConsumePlanned(stack, needs.hunger() >= 800 || com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.ordinaryRawDiet(npc))) continue;
            if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc)!=null){
                int protection=step.params().getOrDefault("source","BAG").equals("BAG")&&stack.is(Items.CARROT)&&com.jgeted.sagadyssey.core.structure.NaturalFarmRules.isFarmer(npc)
                        ?op.equals("CARE")?com.jgeted.sagadyssey.core.structure.NaturalFarmRules.SEEDS:com.jgeted.sagadyssey.core.structure.NaturalFarmRules.eatingProtection(npc):0;
                if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.usable(npc,source,containerId,consumingAs,ItemFingerprint.of(stack),protection,now)<1)continue;
            }
            if (new RightsService(rights).can(consumingAs,AssetRight.CONSUME,containerId,i,ItemFingerprint.of(stack),now).status() == RightsVerdict.Status.ALLOW) { slot=i;break; }
        }
        if (slot < 0) return fail(npc,data,plan,now,"没有有权消费的真实食物");
        var food=source.getItem(slot).copy();
        var work=FieldworkData.get(level.getServer()); ConsumptionPrediction prediction=null;
        boolean trackOutput=step.params().getOrDefault("source","BAG").equals("BAG")&&food.is(Items.CARROT)
                &&com.jgeted.sagadyssey.core.structure.NaturalFarmRules.isFarmer(npc)
                &&work.pendingCargo(npc.getUUID()).getOrDefault("minecraft:carrot#0",0)>0;
        if(trackOutput){
            String review=work.outputConsumptionReview(npc.getUUID(),data::settlement);
            if(!review.isEmpty()||work.pendingDelivery(npc.getUUID()).isPresent())
                return fail(npc,data,plan,now,review.isEmpty()?"原入库转移尚待核对，保留产出":review);
            // ConsumptionService selects its own first lawful slot. Predict that exact quantity-one
            // selection, rather than attributing the meal to the provider's suggested food slot.
            prediction=predictOneConsumption(rights,source,containerId,consumingAs,ItemFingerprint.of(food),key,now);
            if(prediction==null)return fail(npc,data,plan,now,"消费来源、占用或原权属无法核实，暂不扣物");
            int output=prediction.owner().equals(SubjectRef.character(npc.getCharacterId()))?1:0;
            var intent=new FieldworkCargo.OutputConsumption(key,npc.getUUID(),npc.getCharacterId(),containerId,
                    prediction.owner(),"minecraft:carrot#0",1,output,now,-1,prediction.beforeRevision(),-1,FieldworkCargo.ConsumptionState.PENDING);
            try {
                if(!work.beginOutputConsumption(intent))return fail(npc,data,plan,now,"同一产出消费仍未核实，禁止重复扣物");
            } catch (IllegalArgumentException|IllegalStateException conflict) {
                return fail(npc,data,plan,now,"产出消费意图冲突，保留原记录: "+conflict.getMessage());
            }
        }
        data.pending(key);
        var result = new ConsumptionService(rights).consume(containerId,source,consumingAs,ItemFingerprint.of(food),1,key,now);
        if (!result.successful()) {
            if(trackOutput)work.abortOutputConsumption(key);
            data.abort(key); return fail(npc,data,plan,now,"食物被预留或消费权限不足");
        }
        if(trackOutput){
            if(result.consumed()!=1||!matchesActualConsumption(prediction,source,rights,containerId,ItemFingerprint.of(food),result.finalRevision()))
                return fail(npc,data,plan,now,"真实扣物与产出消费意图不一致，保留未决记录，禁止重放");
            try {work.commitOutputConsumption(key,result.consumed(),result.finalRevision(),now);}
            catch(IllegalArgumentException|IllegalStateException conflict){return fail(npc,data,plan,now,"产出消费提交待核对: "+conflict.getMessage());}
        }
        life.feed(recipient.getCharacterId(),NpcFoodItems.hungerRestore(food),op.equals("CARE") ? "家庭成员当面照料" : "真实口粮",now);
        data.done(key); data.defer(npc.getUUID(),now); recipient.playAction(NpcAction.EAT,30);
        resumeWorkScan(npc);
        if (op.equals("CARE")) {
            life.remember(recipient.getCharacterId(),new NpcMemoryRecord("family_care",npc.getCharacterId(),"家庭成员当面提供食物",5,20,now,now+24000),now);
        }
        return TaskTickResult.stepSucceeded("实际消耗一份食物并降低饥饿");
    }

    private record ConsumptionPrediction(int slot,SubjectRef owner,List<ItemStack> before,long beforeRevision) { }
    /** Mirrors only the existing service's quantity-one choice; never grants rights or moves items. */
    private static ConsumptionPrediction predictOneConsumption(RightsStateData rights,Container source,UUID id,
            SubjectRef actor,ItemFingerprint item,String token,long now){
        var context=rights.context(id);if(context==null)return null;
        var before=new ArrayList<ItemStack>();var owners=new HashMap<Integer,SubjectRef>();int physical=0;
        for(int i=0;i<source.getContainerSize();i++){
            var stack=source.getItem(i);before.add(stack.copy());if(!item.matches(stack))continue;
            physical+=stack.getCount();var claim=rights.claimAt(id,i,item);
            owners.put(i,claim==null?context.defaultBeneficialOwner():claim.beneficialOwner());
        }
        if(physical-rights.reservedQuantity(id,item,token,now)<1)return null;
        var resolver=new RightsService(rights);
        for(int i=0;i<source.getContainerSize();i++){
            var stack=source.getItem(i);if(!item.matches(stack)
                    ||resolver.can(actor,AssetRight.CONSUME,id,i,item,now).status()!=RightsVerdict.Status.ALLOW)continue;
            var claim=rights.claimAt(id,i,item);
            if(claim!=null&&claim.quantity()!=stack.getCount())return null;
            if(claim!=null&&!claim.reservations().isEmpty()&&!claim.reservations().contains(token))continue;
            var owner=owners.get(i);int owned=0;
            for(var entry:owners.entrySet())if(owner.equals(entry.getValue()))owned+=before.get(entry.getKey()).getCount();
            if(owned-rights.reservedQuantity(id,item,owner,token,now)>0)
                return new ConsumptionPrediction(i,owner,List.copyOf(before),context.revision());
        }
        return null;
    }
    private static boolean matchesActualConsumption(ConsumptionPrediction prediction,Container source,RightsStateData rights,
            UUID id,ItemFingerprint item,long finalRevision){
        var context=rights.context(id);
        if(prediction==null||context==null||context.revision()!=finalRevision||prediction.beforeRevision()==Long.MAX_VALUE
                ||finalRevision!=prediction.beforeRevision()+1||source.getContainerSize()!=prediction.before().size())return false;
        for(int i=0;i<source.getContainerSize();i++){
            var expected=prediction.before().get(i).copy();if(i==prediction.slot())expected.shrink(1);
            if(!ItemStack.matches(expected,source.getItem(i)))return false;
        }
        var remainder=source.getItem(prediction.slot());var claim=rights.claimAt(id,prediction.slot(),item);
        if(remainder.isEmpty())return claim==null;
        return prediction.owner().equals(claim==null?context.defaultBeneficialOwner():claim.beneficialOwner())
                &&(claim==null||claim.quantity()==remainder.getCount());
    }

    private TaskTickResult conversation(NpcEntityExecutionContext e,NpcPlan plan,NpcPlanStep step,String key,LifeActivityData data) {
        var npc=e.npc(); long now=e.gameTime(); UUID sessionId=UUID.fromString(step.params().get("session"));
        var session=data.session(sessionId,now);
        if (session == null) return fail(npc,data,plan,now,"会话检查点缺失");
        var first=peer(e.level(),session.first()); var second=peer(e.level(),session.second());
        boolean available = participating(first,sessionId) && participating(second,sessionId);
        boolean present = available && first.distanceToSqr(second) <= 16 && first.hasLineOfSight(second)
                && first.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.place())) <= 20.25
                && second.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.place())) <= 20.25
                && NpcBrainRuntime.data(e.level().getServer()).active(first.getUUID()).orElseThrow().currentStep().executorId().equals(ACT_ID())
                && NpcBrainRuntime.data(e.level().getServer()).active(second.getUUID()).orElseThrow().currentStep().executorId().equals(ACT_ID());
        // A successful outcome is shared; a participant's final step may have already retired.
        if (!session.status().terminal()) { session=session.tick(now,present,available); data.session(session); }
        if (session.status() == ConversationSession.Status.CANCELLED || session.status() == ConversationSession.Status.EXPIRED)
            return fail(npc,data,plan,now,"交谈对象离开、撤销预约或等待到期");
        if (session.status() != ConversationSession.Status.SUCCEEDED) {
            if (present) { npc.getLookControl().setLookAt(npc==first?second:first,30,30); if(now%60==0) npc.playAction(NpcAction.HEAD_NOD,20); }
            return TaskTickResult.running(present?"双方同步交谈":"在真实地点等待对方到场");
        }
        String sharedKey="social:"+session.id();
        if (data.settlement(sharedKey)==null) {
            data.pending(sharedKey); var life=NpcLifeData.get(e.level().getServer());
            if (first != null && second != null) {
                for (var pair : List.of(List.of(first,second),List.of(second,first))) {
                    life.adjustRelationship(pair.get(0).getCharacterId(),pair.get(1).getCharacterId(),NpcRelationKind.FRIEND,2,"真实交谈后印象");
                    life.remember(pair.get(0).getCharacterId(),new NpcMemoryRecord("conversation",pair.get(1).getCharacterId(),"在已知地点完成一次交谈",2,10,now,now+24000),now);
                }
            }
            data.done(sharedKey);
        } else if (!"DONE".equals(data.settlement(sharedKey))) return fail(npc,data,plan,now,"社会结算中断，需要核对关系记录");
        data.pending(key); data.done(key); data.defer(npc.getUUID(),now+400);
        resumeWorkScan(npc);
        return TaskTickResult.stepSucceeded("双人会话已结束，关系和记忆仅结算一次");
    }
    private static Identifier ACT_ID() { return LifePlanFactory.ACT; }
    private static void resumeWorkScan(NpcBase npc) {
        var work=com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(MinecraftApi.server(npc));
        work.scan(npc.getUUID()).ifPresent(cursor->work.saveScan(npc.getUUID(),com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkScan.Cursor.begin(cursor.area())));
    }
    private static boolean participating(NpcBase npc,UUID session) {
        return npc!=null && npc.isAlive() && npc.getTarget()==null && npc.getCommand()==NpcCommand.WORK
                && NpcBrainRuntime.isPlanned(npc) && NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID())
                .map(p->session.toString().equals(p.currentStep().params().get("session"))).orElse(false);
    }
    public static NpcBase peer(ServerLevel level,UUID id) { return level.getEntity(id) instanceof NpcBase npc?npc:null; }

    private boolean authorized(NpcBase npc,String op,BlockPos target,Map<String,String> params) {
        var life=NpcLifeData.get(MinecraftApi.server(npc)).record(npc.getCharacterId());
        if(life==null || !life.dimension().equals(params.get("dimension"))) return false;
        if(op.equals("SLEEP")) return life.bedPosition()!=null && target.equals(NpcResidenceFixtures.normalizeBedHead((ServerLevel)npc.level(),life.bedPosition()))
                && npc.level().getBlockState(target).is(BlockTags.BEDS)
                && (npc.isSleeping() || !npc.level().getBlockState(target).getValue(BedBlock.OCCUPIED));
        if(op.equals("EAT")) {
            String source=params.getOrDefault("source","BAG");
            if(source.equals("BAG")) return true;
            if(source.equals("PRIVATE")) return target.equals(life.privateStoragePosition());
            var household=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
            return household!=null && target.equals(household.sharedStorage()) && household.dimension().equals(params.get("dimension"));
        }
        if(op.equals("CARE")) {
            var recipient=peer((ServerLevel)npc.level(),UUID.fromString(params.get("peer")));
            var household=HouseholdData.get(MinecraftApi.server(npc)).forMember(npc.getCharacterId());
            return recipient!=null && household!=null && household.contains(recipient.getCharacterId());
        }
        if(op.equals("HOME")) return target.equals(life.bedPosition()) || target.equals(life.privateStoragePosition());
        if(op.equals("READ")) return knownFacility(npc,target,"READ") && npc.level().getBlockState(target).is(net.minecraft.world.level.block.Blocks.LECTERN)
                && npc.level().getBlockState(target).getValue(LecternBlock.HAS_BOOK);
        if(op.equals("TAVERN")) return knownFacility(npc,target,"TAVERN") && npc.level().getBlockState(target).is(BlockTags.CAMPFIRES);
        if(op.equals("TALK") || op.equals("VISIT")) return true; // Mutual plan/consent validated at rendezvous.
        return op.equals("WAIT") || op.equals("OBSERVE") || op.equals("TAVERN");
    }
    private static boolean knownFacility(NpcBase npc,BlockPos target,String op) {
        return LifeActivityData.get(MinecraftApi.server(npc)).knownPlaces(npc.getUUID()).stream().anyMatch(p->p.position().equals(target)
                && p.dimension().equals(npc.level().dimension().identifier().toString()) &&
                Optional.ofNullable(VisitActivityRegistry.definitions().get(Identifier.parse(p.activity())))
                        .map(a->a.kind().name().equals(op)).orElse(false));
    }
    private TaskTickResult fail(NpcBase npc,LifeActivityData data,NpcPlan plan,long now,String reason) {
        npc.getNavigation().stop(); if(npc.isSleeping())npc.stopSleeping(); data.defer(npc.getUUID(),now);
        data.deferPlace(npc.getUUID(),npc.level().dimension().identifier().toString(),LifePlanFactory.target(plan.currentStep().params()),now);
        resumeWorkScan(npc);
        motions.remove(plan.id()); return TaskTickResult.failed(reason);
    }
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason) {
        motions.remove(p.id());
        if(c instanceof NpcEntityExecutionContext e) {
            e.npc().getNavigation().stop(); if(e.npc().isSleeping())e.npc().stopSleeping();
            LifeActivityData.get(e.level().getServer()).defer(e.npc().getUUID(),e.gameTime());
            String id=s.params().get("session");
            if(id!=null) { var data=LifeActivityData.get(e.level().getServer()); var session=data.session(UUID.fromString(id),e.gameTime()); if(session!=null && !session.status().terminal()) data.session(session.tick(e.gameTime(),false,false)); }
        }
    }
}
