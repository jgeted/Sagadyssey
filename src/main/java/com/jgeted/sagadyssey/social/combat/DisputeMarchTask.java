package com.jgeted.sagadyssey.social.combat;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
public final class DisputeMarchTask implements NpcTaskExecutor {
 public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:dispute_march");
 public static Optional<NpcPlan> choose(NpcBase n,long now){if(!NaturalConflictData.engaged(n.getServer(),n.getCharacterId()))return Optional.empty();var step=new NpcPlanStep(0,NpcPlanPhase.TRAVELLING,ID,Map.of(),NpcPlanStepStatus.PENDING,now,"接续机构有限行动及撤回");return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"dispute-march:"+n.getCharacterId(),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now+40,now+12000,null,"原居民步行接触或撤回，交由已有战斗系统处理实际攻击"));}
 public ResourceLocation id(){return ID;}
 public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){if(!(c instanceof NpcEntityExecutionContext e))return TaskTickResult.failed("行动需要原居民实体");return NaturalConflictData.tickActor(e.npc())?TaskTickResult.running("接续已有机构行动与实际位置"):TaskTickResult.stepSucceeded("行动已经收尾");}
}
