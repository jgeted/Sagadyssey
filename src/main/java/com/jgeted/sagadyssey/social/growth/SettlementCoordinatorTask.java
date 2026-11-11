package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 短劳动片段归原 NPC 调度；持久责任不随片段结束而丢失。 */
public final class SettlementCoordinatorTask implements NpcTaskExecutor {
    public static final Identifier ID = Identifier.parse("sagadyssey:settlement_coordination");

    public static Optional<NpcPlan> choose(NpcBase npc, long now) {
        var development = SettlementDevelopmentData.existing(MinecraftApi.server(npc));
        if (development != null) { var selected=development.choose(npc,now); if(selected.isPresent())return selected; }
        var data = SettlementCoordinatorData.existing(MinecraftApi.server(npc));
        return data == null ? Optional.empty() : data.choose(npc, now);
    }

    static NpcPlan plan(NpcBase npc, String job, long now) {
        var step = new NpcPlanStep(0, NpcPlanPhase.ACTING, ID, Map.of("job", job),
                NpcPlanStepStatus.PENDING, now, "领取已授权的协作任务");
        return new NpcPlan(UUID.randomUUID(), npc.getUUID(), "coordination:" + npc.getCharacterId() + ":" + job,
                ID, NpcPlanKind.COMMITMENT, NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED,
                List.of(step), 0, now, now, now + 40, now + 2400, null, "复用原身份、权限及实物执行器");
    }

    static NpcPlan developmentPlan(NpcBase npc,String project,String job,long now) {
        var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("job",job,"development",project),
                NpcPlanStepStatus.PENDING,now,"领取合法成长工程的短劳动片段");
        return new NpcPlan(UUID.randomUUID(),npc.getUUID(),"development:"+npc.getCharacterId()+":"+project+":"+job,
                ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now+40,now+2400,null,"保留同一工程及原物资账");
    }

    @Override public Identifier id() { return ID; }
    @Override public TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) { return tick(context, plan, step); }
    @Override public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) { return tick(context, plan, step); }
    @Override public void cancel(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step,
                                 com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason reason) {
        if (context instanceof NpcEntityExecutionContext entity) {
            if(step.params().containsKey("development")){
                var d=SettlementDevelopmentData.existing(MinecraftApi.server(entity.npc()));
                if(d!=null)d.yield(entity.npc(),step.params().get("development"),step.params().get("job"),plan.id(),context.gameTime());
                entity.npc().getNavigation().stop();return;
            }
            var data = SettlementCoordinatorData.existing(MinecraftApi.server(entity.npc()));
            if (data != null) data.yield(entity.npc(), step.params().get("job"), plan.id(), context.gameTime());
            entity.npc().getNavigation().stop();
        }
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        if (!(context instanceof NpcEntityExecutionContext entity)) return TaskTickResult.failed("需要原居民实体");
        var npc = entity.npc();
        if(step.params().containsKey("development")){
            var d=SettlementDevelopmentData.existing(MinecraftApi.server(npc));if(d==null)return TaskTickResult.failed("成长协调状态缺失，不重建结果");
            if(context.gameTime()-plan.createdAt()>=200){
                d.yield(npc,step.params().get("development"),step.params().get("job"),plan.id(),context.gameTime());
                npc.getNavigation().stop();return TaskTickResult.stepSucceeded("回到生活调度，保留工程责任");
            }
            var result=d.work(npc,step.params().get("development"),step.params().get("job"),plan.id(),context.gameTime());
            if(result.equals("YIELD"))d.yield(npc,step.params().get("development"),step.params().get("job"),plan.id(),context.gameTime(),true);
            return result.equals("YIELD")?TaskTickResult.stepSucceeded("保留进度等待重检"):TaskTickResult.running(result);
        }
        var data = SettlementCoordinatorData.existing(MinecraftApi.server(npc));
        if (data == null || context.gameTime() - plan.createdAt() >= 200) {
            npc.getNavigation().stop();
            if (data != null) data.yield(npc, step.params().get("job"), plan.id(), context.gameTime());
            return TaskTickResult.stepSucceeded("返回生活调度，保留任务与原携货责任");
        }
        String result = data.work(npc, step.params().get("job"), plan.id(), context.gameTime());
        if (result.equals("YIELD")) data.yield(npc, step.params().get("job"), plan.id(), context.gameTime());
        return result.equals("YIELD") ? TaskTickResult.stepSucceeded("保留真实进度并等待相应条件")
                : TaskTickResult.running(result);
    }
}
