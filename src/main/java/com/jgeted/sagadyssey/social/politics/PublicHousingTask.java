package com.jgeted.sagadyssey.social.politics;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
public final class PublicHousingTask implements NpcTaskExecutor {
 public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:public_housing");
 public static Optional<NpcPlan> choose(NpcBase n,long now){var d=NaturalPolityData.existing(n.getServer());if(d==null||d.projectFor(n.getServer(),n.getCharacterId())==null)return Optional.empty();var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of(),NpcPlanStepStatus.PENDING,now,"履行已有公共床位改善决定");return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"public-housing:"+n.getCharacterId(),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now+40,now+12000,null,"实际劳动、原料和施工口粮换一张新床位"));}
 public ResourceLocation id(){return ID;}
 public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){return tick(c,p,step);}
 public TaskTickResult tick(NpcExecutionContext c,NpcPlan p,NpcPlanStep step){if(!(c instanceof NpcEntityExecutionContext e))return TaskTickResult.failed("公共建设需要原劳动者实体");var n=e.npc();var d=NaturalPolityData.get(n.getServer());var row=d.projectFor(n.getServer(),n.getCharacterId());if(row==null)return TaskTickResult.stepSucceeded("公共项目已结束");if(n.getCommand()!=com.jgeted.sagadyssey.npc.entity.NpcCommand.WORK||!java.util.Objects.equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(n).workplaceSettlementId(),NaturalPolityData.building(row).settlementId())){n.getNavigation().stop();return TaskTickResult.running("工作命令或公共劳动授权改变，保留实际材料和原项目");}String result=d.work(n);if(result.equals("MOVE")){var route=FieldworkRoutes.plan(n,NaturalVillageLayout.at(NaturalPolityData.building(row),NaturalVillageLayout.PUBLIC),false);if(route!=null)FieldworkRoutes.move(n,route.stand());}return result.equals("DONE")?TaskTickResult.stepSucceeded("实际床位改善已结算"):TaskTickResult.running("核验公共材料、实际劳动和设施放置条件");}
}
