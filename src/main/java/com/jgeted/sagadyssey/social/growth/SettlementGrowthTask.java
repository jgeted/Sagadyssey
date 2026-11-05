package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Short, interruptible work slices in the existing NPC scheduler. */
public final class SettlementGrowthTask implements NpcTaskExecutor {
    public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:settlement_growth");
    public static Optional<NpcPlan> choose(NpcBase npc,long now){
        var data=SettlementGrowthData.existing(npc.getServer());
        if(data==null||!data.canWork(npc))return Optional.empty();
        var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of(),NpcPlanStepStatus.PENDING,now,"履行已批准的本聚落发展职责");
        return Optional.of(new NpcPlan(UUID.randomUUID(),npc.getUUID(),"growth:"+npc.getCharacterId(),ID,
                NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,
                now,now,now+40,now+2400,null,"真实材料、劳动和动物喂养；生活与旧义务优先"));
    }
    @Override public ResourceLocation id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
    @Override public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){
        if(!(c instanceof NpcEntityExecutionContext e))return TaskTickResult.failed("发展工作需要原居民实体");
        var n=e.npc();var d=SettlementGrowthData.existing(n.getServer());
        if(d==null||!d.canWork(n)||c.gameTime()-p.createdAt()>=200){n.getNavigation().stop();return TaskTickResult.stepSucceeded("保留工程进度，返回正常生活和工作调度");}
        String result=d.work(n);
        if(result.equals("YIELD"))d.defer(n);
        return result.equals("YIELD")?TaskTickResult.stepSucceeded("当前材料或目标未就绪，交还调度"):TaskTickResult.running(result);
    }
}
