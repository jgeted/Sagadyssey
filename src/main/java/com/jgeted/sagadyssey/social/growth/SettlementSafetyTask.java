package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Work/withdrawal use the existing single NPC plan executor. */
public final class SettlementSafetyTask implements NpcTaskExecutor {
    public static final Identifier ID=Identifier.parse("sagadyssey:settlement_safety");
    public static Optional<NpcPlan> choose(NpcBase n,long now,boolean urgentOnly) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(n));if(d==null||d.resident(n)==null)return Optional.empty();
        boolean retreat=SettlementSafetyRuntime.withdrawal(n,now),night=SettlementSafetyRuntime.nightOutside(n);
        var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(n)).needs(n.getCharacterId());
        if(!retreat&&night&&needs!=null&&needs.hunger()>=800)return Optional.empty();
        String key;
        if(retreat||night)key="REFUGE";
        else {if(urgentOnly)return Optional.empty();var task=d.choose(n,now);if(task==null)return Optional.empty();key=task.getStringOr("Id", "");}
        var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("safety",key),NpcPlanStepStatus.PENDING,now,"核验实际威胁、光照与原材料");
        return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"safety:"+n.getCharacterId()+":"+key,ID,
                NpcPlanKind.COMMITMENT,retreat?NpcPlanPriority.CRITICAL:NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,
                List.of(step),0,now,now,now,now+400,null,"有限安全工作，不改变原人物、库存或义务"));
    }
    @Override public Identifier id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){
        if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();
    }
    @Override public TaskTickResult tick(NpcExecutionContext c,NpcPlan plan,NpcPlanStep step){
        if(!(c instanceof NpcEntityExecutionContext e))return TaskTickResult.failed("安全工作需要原居民实体");
        var n=e.npc();var d=SettlementSafetyData.existing(MinecraftApi.server(n));
        if(d==null||c.gameTime()-plan.createdAt()>=SettlementSafetyPolicy.WORK_SLICE){n.getNavigation().stop();return TaskTickResult.stepSucceeded("保留安全责任，返回生活调度");}
        if(step.params().get("safety").equals("REFUGE")) {
            boolean retreat=SettlementSafetyRuntime.withdrawal(n,c.gameTime());
            if(!retreat&&!SettlementSafetyRuntime.nightOutside(n)){n.getNavigation().stop();return TaskTickResult.stepSucceeded("威胁已解除或已进入有照明屋内，恢复正常调度");}
            var safe=SettlementSafetyRuntime.refuge(n);
            if(safe==null){n.reportWorkActivity("无可达安全住所",null,"保留缺口，不传送、不补建筑");return TaskTickResult.running("没有可达且有照明的避险点");}
            if(n.blockPosition().distSqr(safe)>2)SettlementGrowthData.walkTo(n,safe);else n.getNavigation().stop();
            n.reportWorkActivity("有限避险",safe,"实际步行，等待威胁解除；不回血、不免除原工作");
            return TaskTickResult.running("实际撤向已知有照明住所");
        }
        String result=d.work(n,step.params().get("safety"),c.gameTime());
        return result.equals("YIELD")?TaskTickResult.stepSucceeded("保留照明材料与等待原因"):TaskTickResult.running(result);
    }
}
