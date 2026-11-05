package com.jgeted.sagadyssey.social.household;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
/** Migration reuses the one persistent NPC plan and existing bounded navigation queue. */
public final class HouseholdMigrationTask implements NpcTaskExecutor {
 public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:household_migration");
 public static Optional<NpcPlan> choose(NpcBase n,long now){if(!NaturalHouseholdMigrationData.moving(n.getServer(),n.getCharacterId()))return Optional.empty();var step=new NpcPlanStep(0,NpcPlanPhase.TRAVELLING,ID,Map.of(),NpcPlanStepStatus.PENDING,now,"接续本户已有迁移申请");return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"household-migration:"+n.getCharacterId(),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now+40,now+12000,null,"随原家庭沿实际道路迁往同意接纳的住宅"));}
 public ResourceLocation id(){return ID;}
 public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){if(!(c instanceof NpcEntityExecutionContext e))return TaskTickResult.failed("迁移需要原居民实体");return ResidentWorkMigrationData.tickActor(e.npc())||NaturalHouseholdMigrationData.tickActor(e.npc())?TaskTickResult.running("保留原居民、财物和途中位置"):TaskTickResult.stepSucceeded("迁移已完成");}
}
