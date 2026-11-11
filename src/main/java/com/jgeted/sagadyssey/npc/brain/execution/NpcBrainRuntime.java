package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithForgeTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithOrderPlanFactory;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithReserveOrderTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithSettleTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithTaskIds;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithPlanTermination;
import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithRestPlanFactory;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanLifecycleService;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.social.production.ProductionBatchData;
import com.jgeted.sagadyssey.social.production.ProductionCommitmentService;
import com.jgeted.sagadyssey.social.production.ProductionMaterialRequestData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Optional;

/** 服务端实体桥的集中装配点；未知 NPC 始终保持旧行为模式。 */
public final class NpcBrainRuntime {
    private static final Identifier DIAGNOSTIC_PROVIDER =
            Identifier.fromNamespaceAndPath("sagadyssey", "diagnostic");
    private static final NpcTaskExecutorRegistry EXECUTORS = createExecutors();
    private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

    private NpcBrainRuntime() {
    }

    public static boolean isPlanned(NpcBase npc) {
        MinecraftServer server = MinecraftApi.server(npc);
        return server != null && NpcBrainData.get(server).isPlanned(npc.getUUID());
    }

    public static NpcBehaviorMode mode(NpcBase npc) {
        MinecraftServer server = MinecraftApi.server(npc);
        return server == null ? NpcBehaviorMode.LEGACY
                : NpcBrainData.get(server).mode(npc.getUUID());
    }

    public static void setMode(NpcBase npc, NpcBehaviorMode mode) {
        MinecraftServer server = requireServer(npc);
        RuntimeState state = state(server);
        if (mode == NpcBehaviorMode.LEGACY && state.data.active(npc.getUUID()).isPresent()) {
            closeProductionCommitment(state.data.active(npc.getUUID()).orElse(null), server,
                    context(npc).gameTime(), "管理员切换回旧行为模式");
            state.execution.cancel(context(npc), PlanCancellationReason.MANUAL,
                    "管理员切换回旧行为模式");
        }
        state.data.setMode(npc.getUUID(), mode);
        com.jgeted.sagadyssey.worldsim.lod.NpcLodRuntime.forgetLoaded(npc);
        com.jgeted.sagadyssey.worldsim.lod.NpcLodBudgets.planning(server).forget(npc.getUUID());
        state.idleSince.remove(npc.getUUID());
        state.warnedIdle.remove(npc.getUUID());
        if (mode == NpcBehaviorMode.PLANNED) {
            if (npc.isSleeping()) npc.stopSleeping();
            if (npc.isPassenger()) npc.dismountToBind();
        }
        npc.getNavigation().stop();
    }

    public static NpcPlanExecutionUpdate tick(NpcBase npc) {
        long started=System.nanoTime();
        try {return tickInternal(npc);}
        finally {if(MinecraftApi.server(npc)!=null)NpcPlanningMetrics.record(MinecraftApi.server(npc),System.nanoTime()-started);}
    }

    private static NpcPlanExecutionUpdate tickInternal(NpcBase npc) {
        MinecraftServer server = requireServer(npc);
        if(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.controls(server,npc.getCharacterId())){npc.getNavigation().stop();return NpcPlanExecutionUpdate.idle("聚落尚在远方结算或恢复，保留原作业与实物");}
        RuntimeState state = state(server);
        long now = context(npc).gameTime();
        if (!com.jgeted.sagadyssey.worldsim.lod.NpcLodRuntime.loaded(npc))
            return NpcPlanExecutionUpdate.idle("LOD 检查点需要管理员核对；不恢复或复制资产");
        // Goal eligibility is refreshed less often than executor ticks. Intercept a new
        // threat here too, before a final work tick can incorrectly fail the obligation.
        if (npc.getTarget() != null && npc.getTarget().isAlive()) {
            return cancel(npc, PlanCancellationReason.THREAT, "实际威胁抢占；保留原义务并释放工作预留");
        }
        var productionPlan=state.data.active(npc.getUUID()).orElse(null);
        if(productionPlan!=null&&productionPlan.providerId().equals(BlacksmithTaskIds.PROVIDER)) {
            var encoded=productionPlan.currentStep().params().get(BlacksmithTaskIds.ORDER_ID);
            var obligation=encoded==null?null:ProductionOrderData.get(server).byId(UUID.fromString(encoded)).orElse(null);
            if(obligation!=null&&!obligation.terminal()&&!mayWorkOrder(npc,obligation,now))
                return cancel(npc,PlanCancellationReason.AUTHORIZED_COMMAND,"原生产委托的工作权威已失效，正式取消并释放未消费物资");
        }
        com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.maintain(npc);
        if (state.data.isPlanned(npc.getUUID())) com.jgeted.sagadyssey.social.needs.SupplyFlowService.tick(npc);
        if (state.data.isPlanned(npc.getUUID())) com.jgeted.sagadyssey.social.religion.ReligionTask.maintain(npc);
        var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(npc.getCharacterId());
        var interrupted=state.data.active(npc.getUUID()).orElse(null);
        if(needs!=null && needs.hunger()>=800 && interrupted!=null
                && interrupted.currentStep().phase()!=NpcPlanPhase.SETTLING
                && !"EAT".equals(interrupted.currentStep().params().get("operation"))
                && !(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.isFarmer(npc)
                    &&interrupted.providerId().equals(FieldworkPlanFactory.PROVIDER)
                    &&"PLANT".equals(interrupted.currentStep().params().get("operation")))
                && FieldworkData.get(server).outstandingScaffolds(npc.getUUID()).isEmpty()) {
            var urgent=LifePlanProvider.chooseUrgent(npc,now);
            if(urgent.isPresent() && "EAT".equals(urgent.get().currentStep().params().get("operation"))) {
                cancel(npc,PlanCancellationReason.URGENT_NEED,"严重饥饿，在原子动作边界转入真实进食");
                state.lifecycle.save(urgent.get());
            }
        }
        var resting=state.data.active(npc.getUUID()).orElse(null);
        if(resting!=null&&resting.providerId().equals(LifePlanFactory.PROVIDER)
                &&Set.of("SLEEP","WAIT","HOME").contains(resting.currentStep().params().getOrDefault("operation",""))
                &&FieldworkData.get(server).outstandingScaffolds(npc.getUUID()).isEmpty()
                &&com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodEmergency(npc)
                &&(FieldworkPlanProvider.chooseEmergency(npc,now).isPresent()
                    ||com.jgeted.sagadyssey.social.needs.SupplyTransportTask.hasEmergencyRoute(npc,now)))
            cancel(npc,PlanCancellationReason.URGENT_NEED,"本户急需真实食物，在安全休息边界唤醒原责任人");
        NpcPlan activeLife = state.data.active(npc.getUUID()).orElse(null);
        if(activeLife!=null && activeLife.providerId().equals(LifePlanFactory.PROVIDER) && npc.getCommand()!=NpcCommand.WORK
                && !"EAT".equals(activeLife.currentStep().params().get("operation")))
            state.execution.cancel(context(npc),PlanCancellationReason.MANUAL,"明确命令改变，结束生活计划");
        NpcPlan activeFieldwork = state.data.active(npc.getUUID()).orElse(null);
        if (activeFieldwork != null && activeFieldwork.providerId().equals(BlacksmithRestPlanFactory.PROVIDER)
                && (npc.getCommand() != NpcCommand.WORK || npc.getProfession() != NpcProfession.BLACKSMITH)) {
            state.execution.cancel(context(npc), PlanCancellationReason.MANUAL, "明确命令或职业改变，结束铁匠休整");
            activeFieldwork = state.data.active(npc.getUUID()).orElse(null);
        }
        if (activeFieldwork != null && activeFieldwork.providerId().equals(FieldworkPlanFactory.PROVIDER)
                && (npc.getCommand() != NpcCommand.WORK || !FieldworkDiscovery.producer(npc)
                || !FieldworkPlanProvider.compatible(npc, activeFieldwork))) {
            state.execution.cancel(context(npc), PlanCancellationReason.MANUAL, "明确命令或职业改变，停止职业计划并保留回收义务");
        }
        if (!state.reconciledHistory.contains(npc.getUUID())) {
            boolean reconciled = true;
            for (NpcPlan terminal : BlacksmithPlanTermination.historyToClose(
                    state.data.history(npc.getUUID()), state.data.active(npc.getUUID()))) {
                reconciled &= closeProductionCommitment(terminal, server, now,
                        "终态计划的生产承诺补做收尾");
            }
            if (reconciled) state.reconciledHistory.add(npc.getUUID());
        }
        var planningBudget = com.jgeted.sagadyssey.worldsim.lod.NpcLodBudgets.planning(server);
        if (state.data.active(npc.getUUID()).isPresent()) planningBudget.forget(npc.getUUID());
        if (state.data.active(npc.getUUID()).isEmpty()) {
            boolean urgent = needs != null && needs.hunger() >= 800
                    ||com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodEmergency(npc);
            if (!urgent && !planningBudget.request(npc.getUUID(), now)) {
                npc.reportWorkActivity("等待规划调度", null, "公平队列分摊新任务检索；当前义务与紧急处置不受限");
                return NpcPlanExecutionUpdate.idle("等待规划预算");
            }
            if (urgent) planningBudget.forget(npc.getUUID());
            long selectionStarted = System.nanoTime();
            try { chooseNextPlan(npc, state, now); }
            finally {
                long elapsed = System.nanoTime() - selectionStarted;
                planningBudget.charge(elapsed);
                com.jgeted.sagadyssey.worldsim.lod.LodMetrics.record(server,
                        com.jgeted.sagadyssey.worldsim.lod.LodMetrics.Kind.SELECTION, elapsed);
            }
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            long since = state.idleSince.computeIfAbsent(npc.getUUID(), ignored -> now);
            if (!FieldworkDiscovery.producer(npc) && now - since >= 200L && state.warnedIdle.add(npc.getUUID())) {
                Sagadyssey.LOGGER.warn("PLANNED NPC {} has no active plan for {} ticks", npc.getUUID(), now - since);
            }
        } else {
            state.idleSince.remove(npc.getUUID());
            state.warnedIdle.remove(npc.getUUID());
        }
        state.data.active(npc.getUUID()).ifPresent(com.jgeted.sagadyssey.npc.brain.command.NpcInterruptionData.get(server)::continued);
        NpcPlanExecutionUpdate update = state.execution.tick(context(npc));
        if (BlacksmithPlanTermination.closesCommitment(update)) {
            if (!closeProductionCommitment(update.plan(), server, now, update.detail())) state.reconciledHistory.remove(npc.getUUID());
        }
        return update;
    }

    private static void chooseNextPlan(NpcBase npc, RuntimeState state, long now) {
        if (state.data.active(npc.getUUID()).isEmpty()) com.jgeted.sagadyssey.social.growth.SettlementSafetyTask.choose(npc,now,true).ifPresent(state.lifecycle::save);
        if (state.data.active(npc.getUUID()).isEmpty()) LifePlanProvider.chooseUrgent(npc,now).ifPresent(state.lifecycle::save);
        if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.household.HouseholdMigrationTask.choose(npc,now).ifPresent(state.lifecycle::save);
        if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.combat.DisputeMarchTask.choose(npc,now).ifPresent(state.lifecycle::save);
        if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.politics.PublicHousingTask.choose(npc,now).ifPresent(state.lifecycle::save);
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.choose(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.npc.brain.command.NpcCommandTask.choose(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            if(com.jgeted.sagadyssey.social.needs.SupplyTransportTask.hasEmergencyRoute(npc,now))
                com.jgeted.sagadyssey.social.needs.SupplyTransportTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())FieldworkPlanProvider.chooseEmergency(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            LifePlanProvider.choose(npc,now,false).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.social.religion.life.ChurchLifeTask.choose(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.social.religion.ReligionTask.choose(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.social.needs.SupplyTransportTask.choose(npc, now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.needs.MerchantSupplyTask.choose(npc,now).ifPresent(state.lifecycle::save);
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            com.jgeted.sagadyssey.social.growth.SettlementSafetyTask.choose(npc,now,false).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.growth.SettlementCoordinatorTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.industry.common.IndustryToolTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.industry.common.EntityResourceTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())tryCreateBlacksmithOrderPlan(npc, state, now);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.growth.SettlementGrowthTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if(state.data.active(npc.getUUID()).isEmpty())com.jgeted.sagadyssey.social.industry.forestry.TreePlantTask.choose(npc,now).ifPresent(state.lifecycle::save);
            if (state.data.active(npc.getUUID()).isEmpty()) {
                var work=FieldworkPlanProvider.choose(npc,now);
                if(work.isPresent() && work.get().currentStep().executorId().equals(FieldworkPlanFactory.REST)) {
                    var life=LifePlanProvider.choose(npc,now,true); state.lifecycle.save(life.orElse(work.get()));
                } else if(work.isPresent()) state.lifecycle.save(work.get());
                else if(!FieldworkDiscovery.producer(npc))LifePlanProvider.choose(npc,now,true).ifPresent(state.lifecycle::save);
            }
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            if (npc.getCommand() == NpcCommand.WORK && npc.getProfession() == NpcProfession.BLACKSMITH) {
                state.lifecycle.save(BlacksmithRestPlanFactory.create(npc.getUUID(),
                        npc.level().dimension().identifier().toString(), npc.blockPosition(), now));
                npc.reportWorkActivity("铁匠休整", null, "没有当前可执行订单或生活安排，短暂休整后重新核验");
            }
        }
        if (state.data.active(npc.getUUID()).isEmpty()) {
            if (com.jgeted.sagadyssey.social.needs.SupplyTransportTask.eligible(npc)
                    || com.jgeted.sagadyssey.social.religion.ReligionTask.eligible(npc)
                    || com.jgeted.sagadyssey.social.religion.life.ChurchLifeTask.eligible(npc)
                    || npc.getCommand()==NpcCommand.WORK && com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.eligible(npc)) {
                createDiagnosticWait(npc,100);
                npc.reportWorkActivity(com.jgeted.sagadyssey.social.religion.ReligionTask.eligible(npc)?"教堂值守休整":"物流休整",null,"没有当前可领取任务，有限等待后重新核验");
            }
        }
    }

    public static void suspend(NpcBase npc) {
        var state = state(requireServer(npc));
        state.execution.suspend(npc.getUUID());
        state.data.active(npc.getUUID()).ifPresent(p -> state.reservations.releasePlan(p.id()));
        state.reconciledHistory.remove(npc.getUUID());
        com.jgeted.sagadyssey.worldsim.lod.NpcLodBudgets.planning(MinecraftApi.server(npc)).forget(npc.getUUID());
    }

    /** 为测试入口或其他服务把指定生产订单绑定为当前铁匠计划。 */
    public static NpcPlan createBlacksmithOrderPlan(NpcBase npc, UUID orderId) {
        MinecraftServer server = requireServer(npc);
        RuntimeState state = state(server);
        if (!state.data.isPlanned(npc.getUUID())) {
            throw new IllegalStateException("npc must use PLANNED mode");
        }
        if (state.data.active(npc.getUUID()).isPresent()) {
            throw new IllegalStateException("npc already has an active plan");
        }
        ProductionOrderData orders = ProductionOrderData.get(server);
        ProductionOrder order = orders.byId(orderId)
                .orElseThrow(() -> new IllegalStateException("production order does not exist"));
        long now = context(npc).gameTime();
        if (order.status() == ProductionOrderStatus.OPEN) {
            BlockPos workstation = order.workstationPosition() == null
                    ? findBlacksmithWorkstation((ServerLevel) npc.level(), order) : null;
            if (order.workstationPosition() == null && workstation == null) {
                throw new IllegalStateException("no available anvil or smithing table near output container");
            }
            order = workstation == null
                    ? order.claim(npc.getCharacterId(), now, "铁匠领取生产订单")
                    : order.claim(npc.getCharacterId(), npc.level().dimension().identifier().toString(),
                    workstation, now, "铁匠领取生产订单并选择工作站");
            orders.put(order);
        }
        if (!npc.getCharacterId().equals(order.assignedWorkerId())) {
            throw new IllegalStateException("production order belongs to another worker");
        }
        NpcPlan plan = planForOrder(npc, order, now);
        state.lifecycle.save(plan);
        state.idleSince.remove(npc.getUUID());
        state.warnedIdle.remove(npc.getUUID());
        return plan;
    }

    public static NpcPlanExecutionUpdate cancel(NpcBase npc,
                                                 PlanCancellationReason reason,
                                                 String detail) {
        MinecraftServer server = requireServer(npc);
        RuntimeState state = state(server);
        NpcPlan interruptedPlan=state.data.active(npc.getUUID()).orElse(null);
        if(interruptedPlan!=null && (reason==PlanCancellationReason.THREAT || reason==PlanCancellationReason.URGENT_NEED)) {
            com.jgeted.sagadyssey.npc.brain.command.NpcInterruptionData.get(server).interrupted(interruptedPlan,npc.getCharacterId(),reason,context(npc).gameTime());
            if(interruptedPlan.providerId().equals(BlacksmithTaskIds.PROVIDER)) {
                String orderId=interruptedPlan.currentStep().params().get(BlacksmithTaskIds.ORDER_ID);
                if(orderId!=null)ProductionOrderData.get(server).byId(UUID.fromString(orderId)).ifPresent(order ->
                    RightsStateData.get(server).releaseReservations("production:"+order.batchId()));
            }
        }
        if (reason == PlanCancellationReason.NPC_DEATH
                || reason == PlanCancellationReason.MANUAL
                || reason == PlanCancellationReason.AUTHORIZED_COMMAND
                || reason == PlanCancellationReason.TARGET_INVALID) {
            closeProductionCommitment(state.data.active(npc.getUUID()).orElse(null), server,
                    context(npc).gameTime(), detail == null ? reason.name() : detail);
        }
        NpcPlanExecutionUpdate update = state.execution.cancel(context(npc), reason, detail);
        state.idleSince.remove(npc.getUUID());
        state.warnedIdle.remove(npc.getUUID());
        return update;
    }

    public static NpcBrainData data(MinecraftServer server) {
        return state(server).data;
    }

    /** 游戏测试重载桥；调用者先从磁盘装回 SavedData，下一 tick 重新恢复每个运行步骤。 */
    public static synchronized void rebuildAfterDataReload(MinecraftServer server) {
        STATES.remove(requireNonNullServer(server));
    }
    /** The integrated client may open another world in the same JVM; release the stopped server. */
    public static synchronized void forgetServer(MinecraftServer server) {
        STATES.remove(requireNonNullServer(server));
    }
    private static MinecraftServer requireNonNullServer(MinecraftServer server) {
        return java.util.Objects.requireNonNull(server, "server");
    }

    public static NpcReservationService reservations(MinecraftServer server) {
        return state(server).reservations;
    }

    /**
     * 创建不会修改世界的短等待计划，供管理员确认保存、重载与实体桥是否连通。
     */
    public static NpcPlan createDiagnosticWait(NpcBase npc, int durationTicks) {
        if (durationTicks < 1 || durationTicks > 72_000) {
            throw new IllegalArgumentException("durationTicks must be between 1 and 72000");
        }
        MinecraftServer server = requireServer(npc);
        RuntimeState state = state(server);
        if (!state.data.isPlanned(npc.getUUID())) {
            throw new IllegalStateException("npc must use PLANNED mode");
        }
        if (state.data.active(npc.getUUID()).isPresent()) {
            throw new IllegalStateException("npc already has an active plan");
        }
        long now = context(npc).gameTime();
        long until = now + durationTicks;
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING,
                WaitUntilTaskExecutor.ID,
                Map.of(WaitUntilTaskExecutor.UNTIL_GAME_TIME, Long.toString(until)),
                NpcPlanStepStatus.PENDING, now, "等待桥接测试");
        NpcPlan plan = new NpcPlan(UUID.randomUUID(), npc.getUUID(),
                "diagnostic:wait:" + npc.getUUID() + ":" + now,
                DIAGNOSTIC_PROVIDER, NpcPlanKind.AMBIENT, NpcPlanPriority.AMBIENT,
                NpcPlanStatus.SELECTED, List.of(step), 0, now, now,
                now + Math.min(20L, durationTicks), until + 200L,
                null, "管理员创建的无副作用等待计划");
        state.lifecycle.save(plan);
        state.idleSince.remove(npc.getUUID());
        state.warnedIdle.remove(npc.getUUID());
        return plan;
    }

    private static synchronized RuntimeState state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, ignored -> {
            NpcBrainData data = NpcBrainData.get(server);
            NpcReservationService reservations = new NpcReservationService();
            NpcPlanLifecycleService lifecycle = new NpcPlanLifecycleService(data, reservations);
            return new RuntimeState(data, reservations, lifecycle,
                    new NpcPlanExecutionService(data, lifecycle, EXECUTORS),
                    new HashMap<>(), new HashSet<>(), new HashSet<>());
        });
    }

    private static NpcTaskExecutorRegistry createExecutors() {
        NpcTaskExecutorRegistry registry = new NpcTaskExecutorRegistry();
        registry.register(new WaitUntilTaskExecutor());
        registry.register(new com.jgeted.sagadyssey.social.combat.DisputeMarchTask());
        registry.register(new com.jgeted.sagadyssey.social.politics.PublicHousingTask());
        registry.register(new com.jgeted.sagadyssey.social.growth.SettlementGrowthTask());
        registry.register(new com.jgeted.sagadyssey.social.growth.SettlementCoordinatorTask());
        registry.register(new com.jgeted.sagadyssey.social.industry.common.EntityResourceTask());
        registry.register(new com.jgeted.sagadyssey.social.industry.common.IndustryToolTask());
        registry.register(new com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter());
        registry.register(new com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask());
        registry.register(new com.jgeted.sagadyssey.social.growth.SettlementSafetyTask());
        registry.register(new com.jgeted.sagadyssey.social.household.HouseholdMigrationTask());
        registry.register(new MoveToBlockTaskExecutor());
        registry.register(new BlacksmithReserveOrderTaskExecutor());
        registry.register(new BlacksmithForgeTaskExecutor());
        registry.register(new BlacksmithSettleTaskExecutor());
        registry.register(new FieldworkTaskExecutor(FieldworkPlanFactory.RESERVE));
        registry.register(new FieldworkTaskExecutor(FieldworkPlanFactory.MOVE));
        registry.register(new FieldworkTaskExecutor(FieldworkPlanFactory.ACT));
        registry.register(new FieldworkTaskExecutor(FieldworkPlanFactory.REST));
        registry.register(new FieldworkDepositTaskExecutor());
        registry.register(new FieldworkToolTaskExecutor());
        registry.register(new FieldworkSeedTaskExecutor());
        registry.register(new LifeTaskExecutor(LifePlanFactory.RESERVE));
        registry.register(new LifeTaskExecutor(LifePlanFactory.MOVE));
        registry.register(new LifeTaskExecutor(LifePlanFactory.ACT));
        registry.register(new com.jgeted.sagadyssey.social.needs.SupplyTransportTask());
        registry.register(new com.jgeted.sagadyssey.social.needs.MerchantSupplyTask());
        registry.register(new com.jgeted.sagadyssey.social.religion.ReligionTask());
        registry.register(new com.jgeted.sagadyssey.social.religion.life.ChurchLifeTask());
        registry.register(new com.jgeted.sagadyssey.npc.brain.command.NpcCommandTask());
        registry.register(new com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask());
        return registry;
    }
    public static void installLifePlan(NpcBase npc,NpcPlan plan) {
        var state=state(requireServer(npc));
        if(!isPlanned(npc)||!plan.npcId().equals(npc.getUUID())||!plan.providerId().equals(LifePlanFactory.PROVIDER)||state.data.active(npc.getUUID()).isPresent())
            throw new IllegalStateException("invalid life plan installation");
        state.lifecycle.save(plan);
    }

    private static Optional<NpcPlan> tryCreateBlacksmithOrderPlan(NpcBase npc,
                                                                  RuntimeState state,
                                                                  long now) {
        if (npc.getProfession() != NpcProfession.BLACKSMITH
                || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null
                || !NpcSchedulePlanner.allowsWork(npc)) {
            return Optional.empty();
        }
        ProductionOrderData orders = ProductionOrderData.get(requireServer(npc));
        ProductionOrder order = orders.assignedTo(npc.getCharacterId()).stream()
                .filter(value -> blacksmithDispatchable(value) && readyForRetry(value, now))
                .findFirst().orElse(null);
        if (order == null) {
            for (ProductionOrder candidate : orders.openOrders()) {
                if(!blacksmithDispatchable(candidate)||!mayWorkOrder(npc,candidate,now)) continue;
                String currentDimension = npc.level().dimension().identifier().toString();
                if (!candidate.targetContainerDimension().equals(currentDimension)
                        || (candidate.workstationDimension() != null
                        && !candidate.workstationDimension().equals(currentDimension))) continue;
                BlockPos workstation = candidate.workstationPosition() == null
                        ? findBlacksmithWorkstation((ServerLevel) npc.level(), candidate) : null;
                if (candidate.workstationPosition() == null && workstation == null) continue;
                order = workstation == null
                        ? candidate.claim(npc.getCharacterId(), now, "铁匠自动领取生产订单")
                        : candidate.claim(npc.getCharacterId(), currentDimension, workstation, now,
                        "铁匠自动领取生产订单并选择工作站");
                orders.put(order);
                break;
            }
        }
        if (order == null) return Optional.empty();
        NpcPlan plan = planForOrder(npc, order, now);
        state.lifecycle.save(plan);
        return Optional.of(plan);
    }

    /** Keep named workshop/native-machine contracts with their existing execution adapters. */
    public static boolean blacksmithDispatchable(ProductionOrder order) {
        return !java.util.Set.of(
                com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask.BED,
                com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask.PLANKS,
                com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask.STICKS,
                com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask.FISHING_ROD,
                com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter.RECIPE,
                com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter.BOUND_IRON,
                com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter.BOUND_POTATO).contains(order.recipeId());
    }

    private static boolean readyForRetry(ProductionOrder order, long now) {
        return order.status() != ProductionOrderStatus.BLOCKED || order.blocker() == null
                || order.blocker().retryAt() <= now;
    }

    /** A global order ledger is not an invitation to work for arbitrary strangers. */
    public static boolean mayWorkOrder(NpcBase npc,ProductionOrder order,long now) {
        var issuer=order.issuer();var context=com.jgeted.sagadyssey.social.work.NpcWorkContext.from(npc);
        return switch(issuer.type()) {
            case CHARACTER -> issuer.id().equals(npc.getCharacterId())
                    || issuer.id().equals(context.employerCharacterId())
                    && com.jgeted.sagadyssey.social.service.ServiceRelationshipData.get(MinecraftApi.server(npc))
                    .activeForAgent(npc.getCharacterId(),now).stream().anyMatch(r->r.principalId().equals(issuer.id())
                    &&r.scopes().contains(com.jgeted.sagadyssey.social.service.AuthorityScope.WORK)
                    &&(r.limits().dimension().isBlank()||r.limits().dimension().equals(npc.level().dimension().identifier().toString()))
                    &&(r.limits().workplaceSettlementId()==null||r.limits().workplaceSettlementId().equals(context.workplaceSettlementId())));
            case INSTITUTION -> issuer.id().equals(context.workplaceSettlementId());
            case HOUSEHOLD -> {var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(MinecraftApi.server(npc)).byId(issuer.id());yield home!=null&&home.contains(npc.getCharacterId());}
            case CONTAINER -> false;
        };
    }

    private static NpcPlan planForOrder(NpcBase npc, ProductionOrder order, long now) {
        long lifetime = Math.min(BlacksmithOrderPlanFactory.MAX_PLAN_LIFETIME_TICKS,
                Math.max(2_400L, order.requiredWorkTicks() + 1_200L));
        return BlacksmithOrderPlanFactory.create(UUID.randomUUID(), npc.getUUID(),
                npc.getCharacterId(), order, now, now + lifetime);
    }

    private static BlockPos findBlacksmithWorkstation(ServerLevel level,
                                                       ProductionOrder order) {
        BlockPos center = order.targetContainerDimension()
                .equals(level.dimension().identifier().toString())
                ? order.targetContainerPosition() : BlockPos.ZERO;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(center.offset(-24, -4, -24),
                center.offset(24, 4, 24))) {
            if (!level.hasChunkAt(candidate)) continue;
            var block = level.getBlockState(candidate).getBlock();
            if (block != Blocks.ANVIL && block != Blocks.CHIPPED_ANVIL
                    && block != Blocks.DAMAGED_ANVIL && block != Blocks.SMITHING_TABLE) continue;
            double distance = candidate.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        return best;
    }

    private static boolean closeProductionCommitment(NpcPlan plan, MinecraftServer server,
                                                  long now, String detail) {
        if (plan == null || !plan.providerId().equals(BlacksmithTaskIds.PROVIDER)) return true;
        String encodedOrderId = plan.currentStep().params().get(BlacksmithTaskIds.ORDER_ID);
        if (encodedOrderId == null) return false;
        try {
            UUID orderId = UUID.fromString(encodedOrderId);
            ProductionCommitmentService.close(orderId, ProductionOrderData.get(server),
                    ProductionBatchData.get(server), ProductionMaterialRequestData.get(server),
                    RightsStateData.get(server), now, detail);
            return true;
        } catch (RuntimeException exception) {
            Sagadyssey.LOGGER.error("Failed to close production commitment for plan {}",
                    plan.id(), exception);
            return false;
        }
    }

    private static NpcEntityExecutionContext context(NpcBase npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("NPC brain can only run on a server level");
        }
        return new NpcEntityExecutionContext(npc, level);
    }

    private static MinecraftServer requireServer(NpcBase npc) {
        MinecraftServer server = MinecraftApi.server(npc);
        if (server == null) throw new IllegalStateException("NPC is not attached to a server");
        return server;
    }

    private record RuntimeState(NpcBrainData data,
                                NpcReservationService reservations,
                                NpcPlanLifecycleService lifecycle,
                                NpcPlanExecutionService execution,
                                Map<UUID, Long> idleSince,
                                Set<UUID> warnedIdle,
                                Set<UUID> reconciledHistory) {
    }
}
