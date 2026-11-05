package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import java.util.*;

/** 五种职业步骤共用恢复/预留规则。入库使用独立统一转移执行器。 */
public final class FieldworkTaskExecutor implements NpcTaskExecutor {
    private final ResourceLocation id;
    private final Map<String, Movement> movements = new HashMap<>();
    @Override public void forget(UUID body,UUID plan){movements.keySet().removeIf(k->k.startsWith(plan+":"));}
    public FieldworkTaskExecutor(ResourceLocation id) { this.id = id; }
    @Override public ResourceLocation id() { return id; }
    @Override public TaskTickResult start(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { return execute(c, p, s); }
    @Override public TaskTickResult tick(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { return execute(c, p, s); }
    @Override public TaskTickResult recover(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) {
        movements.remove(p.id() + ":" + s.index());
        return execute(c, p, s);
    }
    @Override public void cancel(NpcExecutionContext c, NpcPlan p, NpcPlanStep s, PlanCancellationReason reason) {
        movements.remove(p.id() + ":" + s.index());
        if (c instanceof NpcEntityExecutionContext e) e.npc().getNavigation().stop();
        if (c instanceof NpcEntityExecutionContext e) NpcBrainRuntime.reservations(e.level().getServer()).releasePlan(p.id());
    }

    private TaskTickResult execute(NpcExecutionContext raw, NpcPlan plan, NpcPlanStep step) {
        if (!(raw instanceof NpcEntityExecutionContext context)) return TaskTickResult.failed("职业作业需要服务端实体");
        var npc = context.npc();
        long now = context.gameTime();
        if (id.equals(FieldworkPlanFactory.REST)) {
            String reason = step.params().get("reason");
            npc.reportWorkActivity("整理工具并短暂休息", null, reason);
            long until = Long.parseLong(step.params().get(WaitUntilTaskExecutor.UNTIL_GAME_TIME));
            if (now >= until) {
                if (!Boolean.parseBoolean(step.params().get("continue_scan"))) {
                    var data = FieldworkData.get(context.level().getServer());
                    data.scan(npc.getUUID()).ifPresent(cursor -> data.saveScan(npc.getUUID(), FieldworkScan.Cursor.begin(cursor.area())));
                }
                return TaskTickResult.stepSucceeded("休整结束，重新选择任务");
            }
            // 有限休整也保持可观察活动；轻微转头，不开展阶段5的社交系统。
            if (now % 40 == 0) npc.getLookControl().setLookAt(npc.getX() + Math.cos(now * .03), npc.getEyeY(), npc.getZ() + Math.sin(now * .03));
            return TaskTickResult.running("有限休整：" + reason);
        }
        TaskTickResult reserved = reserve(context, plan);
        if (reserved != null) return reserved;
        if (id.equals(FieldworkPlanFactory.RESERVE)) return TaskTickResult.stepSucceeded("已预留本计划单方块目标与材料回收位置");
        if (id.equals(FieldworkPlanFactory.MOVE)) {
            BlockPos target = new BlockPos(Integer.parseInt(step.params().get("x")), Integer.parseInt(step.params().get("y")), Integer.parseInt(step.params().get("z")));
            return move(context, plan, step, target, 1.25D, "前往作业位置");
        }
        FieldworkAction action = new FieldworkAction(plan.id(), npc.getUUID(), step.index(), step.params().get("dimension"),
                FieldworkTarget.position(step.params()), FieldworkAction.Operation.valueOf(step.params().get("operation")), step.params().get("expected"));
        var data = FieldworkData.get(context.level().getServer());
        var recorded = data.receipt(action.key()).orElse(null);
        if (recorded != null) {
            if (!recorded.action().equals(action)) return TaskTickResult.failed("回执键与当前作业负载冲突，拒绝不安全恢复");
            return TaskTickResult.stepSucceeded("已有单方块提交回执，未重复动作");
        }
        if (!action.dimension().equals(context.level().dimension().location().toString())) return TaskTickResult.failed("作业维度已变化");
        if (!context.level().hasChunkAt(action.target())) return blocked("CHUNK_UNLOADED", "作业区块暂未加载", now + 100);
        if (action.operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD && context.level().getBlockState(action.target()).isAir()) {
            // 外部破坏没有可回收产物；只关闭义务，绝不能发放一份虚构材料。
            data.record(new FieldworkActionReceipt(action, now, step.params().get("result"), List.of(), 0));
            return TaskTickResult.stepSucceeded("脚手架已被外部移除，无材料可回收");
        }
        boolean standingOnRecovery = action.operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD
                && npc.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(action.target()).move(0, 1, 0));
        boolean building = action.operation() == FieldworkAction.Operation.PLACE_SCAFFOLD;
        BlockPos buildStand = step.params().containsKey("stand_x")
                ? new BlockPos(Integer.parseInt(step.params().get("stand_x")), Integer.parseInt(step.params().get("stand_y")),
                Integer.parseInt(step.params().get("stand_z"))) : null;
        boolean needsBuildStand = building && buildStand != null
                && (Math.abs(npc.getY() - buildStand.getY()) >= .4D
                || npc.distanceToSqr(buildStand.getX() + .5D, buildStand.getY(), buildStand.getZ() + .5D) > .04D);
        if (!FieldworkRoutes.closeEnough(npc, action.target()) || standingOnRecovery || needsBuildStand) {
            BlockPos stand;
            if (step.params().containsKey("stand_x")) stand = new BlockPos(Integer.parseInt(step.params().get("stand_x")),
                    Integer.parseInt(step.params().get("stand_y")), Integer.parseInt(step.params().get("stand_z")));
            else {
                var route = FieldworkRoutes.plan(npc, action.target(), false,
                        action.operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD);
                if (route == null) return unreachable(context, plan, action.target(), "作业位置暂不可达");
                stand = route.stand();
            }
            TaskTickResult travel = move(context, plan, step, stand, .04D, "接近单方块作业目标");
            if (travel.state() != TaskTickResult.State.STEP_SUCCEEDED) return travel;
            if (!FieldworkRoutes.closeEnough(npc, action.target())) return unreachable(context, plan, action.target(), "到达站位后仍不可触及目标");
        }
        FieldworkWorldMutation mutation = new FieldworkWorldMutation(context, action, step.params(), now);
        var result = FieldworkCommitService.commit(data, action, now, (value, time) -> mutation);
        if (result.status() == FieldworkCommitService.Status.BLOCKED) {
            var blocker = result.blocker().orElseThrow();
            npc.reportWorkActivity("检查工具与随身物资", null, "本轮暂缓作业");
            if (!blocker.code().equals("CHUNK_UNLOADED") && !blocker.code().equals("TARGET_RESERVED")
                    && !blocker.code().equals("SCAFFOLD_OCCUPIED")) {
                data.rememberUnreachable(npc.getUUID(), action.dimension(), action.target(), now, now + 200, blocker.code() + ":" + blocker.detail());
                return TaskTickResult.failed(blocker.code() + ":" + blocker.detail());
            }
            return blocked(blocker.code(), blocker.detail(), now + 100);
        }
        if (result.status() == FieldworkCommitService.Status.COMMITTED) {
            mutation.notifyCommitted();
            npc.playAction(switch (action.operation()) {
                case MINE -> NpcAction.MINE;
                case CHOP -> NpcAction.CHOP;
                case TILL, IRRIGATE -> NpcAction.TILL;
                case PLANT -> NpcAction.PLANT;
                case PLACE_SCAFFOLD -> NpcAction.PLACE;
                default -> NpcAction.HARVEST;
            }, 18);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.reportWorkActivity(action.operation() == FieldworkAction.Operation.MINE ? "采集矿物"
                    : action.operation() == FieldworkAction.Operation.CHOP ? "采集木材" : "照料农田", action.target(), "完成一格作业");
        }
        return TaskTickResult.stepSucceeded("单方块、库存、损耗与权利回执已一并提交");
    }

    public static TaskTickResult reserve(NpcEntityExecutionContext context, NpcPlan plan) {
        var service = NpcBrainRuntime.reservations(context.level().getServer());
        long now = context.gameTime(), until = Math.min(plan.expiresAt(), now + 400);
        if (until <= now) return TaskTickResult.failed("计划已过期");
        Set<com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey> keys = new HashSet<>();
        for (var step : plan.steps()) {
            if (!step.params().containsKey("operation")) continue;
            var key = new com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey("fieldwork_block",
                    step.params().get("dimension") + ':' + FieldworkTarget.position(step.params()).asLong());
            if (!keys.add(key)) continue;
            var acquired = service.acquire(key, plan.id(), plan.npcId(), now, until);
            if (!acquired.acquired()) {
                service.releasePlan(plan.id());
                return blocked("TARGET_RESERVED", "目标正由另一名劳动者使用", now + 40);
            }
            service.renew(key, plan.id(), plan.npcId(), now, Math.max(until, acquired.reservation().expiresAt()));
        }
        return null;
    }

    private TaskTickResult move(NpcEntityExecutionContext context, NpcPlan plan, NpcPlanStep step,
                                BlockPos target, double rangeSq, String activity) {
        NpcBase npc = context.npc();
        String key = plan.id() + ":" + step.index();
        if (Math.abs(npc.getY() - target.getY()) < .4D
                && npc.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5) <= rangeSq) {
            npc.getNavigation().stop(); movements.remove(key); return TaskTickResult.stepSucceeded("已到达真实站位");
        }
        Movement movement = movements.computeIfAbsent(key, ignored -> { var value = new Movement(); value.progress.reset(npc, target); return value; });
        npc.reportWorkActivity(activity, null, "正在沿可行路径移动");
        boolean stalled = movement.progress.tick(npc, target);
        // 原版导航到节点边缘就结束；放置/触及检查需要走到安全中心，不能隔一格冒充站位。
        if (rangeSq < 2.25D && Math.abs(npc.getY() - target.getY()) < .4D
                && npc.distanceToSqr(target.getX() + .5D, target.getY(), target.getZ() + .5D) < 2.25D
                && FieldworkRoutes.walkable(npc, target)) {
            if (stalled && ++movement.failures >= 3) {
                movements.remove(key); return unreachable(context, plan, target, "安全站位中心无法推进");
            }
            npc.getNavigation().stop();
            npc.getMoveControl().setWantedPosition(target.getX() + .5D, target.getY(), target.getZ() + .5D, 1D);
            return TaskTickResult.running("沿已验证地面微调到真实站位中心");
        }
        if (context.gameTime() >= movement.repathAt || stalled || npc.getNavigation().isDone()) {
            movement.repathAt = context.gameTime() + 20;
            if (!FieldworkRoutes.move(npc, target) || stalled) {
                if (++movement.failures >= 3) { movements.remove(key); return unreachable(context, plan, target, "路径连续无法推进"); }
            } else movement.failures = 0;
        }
        return TaskTickResult.running("正在前往有效站位");
    }
    private static TaskTickResult unreachable(NpcEntityExecutionContext c, NpcPlan p, BlockPos pos, String reason) {
        var data = FieldworkData.get(c.level().getServer());
        // 记忆真实作业目标，而非恰好失败的导航节点。
        BlockPos target = p.steps().stream().filter(step -> step.params().containsKey("operation"))
                .findFirst().map(step -> FieldworkTarget.position(step.params())).orElse(pos);
        data.rememberUnreachable(c.npcId(), c.level().dimension().location().toString(), target, c.gameTime(), c.gameTime() + 600, reason);
        c.npc().getNavigation().stop();
        return TaskTickResult.failed(reason);
    }
    public static TaskTickResult blocked(String code, String detail, long retryAt) {
        return TaskTickResult.blocked(detail, new NpcPlanBlocker(code, detail, retryAt, null));
    }
    private static final class Movement { final MovementProgress progress = new MovementProgress(); long repathAt; int failures; }
}
