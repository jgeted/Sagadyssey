package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Finite legs share the only noncombat execution bridge. No teleport or remote completion. */
public final class NpcCommandTask implements NpcTaskExecutor {
    public static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath("sagadyssey","command_leg");
    public static final ResourceLocation PROVIDER=ResourceLocation.fromNamespaceAndPath("sagadyssey","player_command");
    private final Map<UUID,MovementProgress> movement=new HashMap<>();
    @Override public void forget(UUID body,UUID plan){movement.remove(plan);}
    @Override public ResourceLocation id(){return ID;}
    public static Optional<NpcPlan> choose(NpcBase npc,long now) {
        var intent=NpcCommandData.get(npc.getServer()).active(npc.getCharacterId()).orElse(null);
        if(intent==null||intent.command()==NpcCommand.WORK||intent.closed()||now>=intent.expires()
                ||npc.getTarget()!=null||!NpcCommandService.authorized(npc,intent,now))return Optional.empty();
        Map<String,String> params=new HashMap<>();params.put("intent",intent.id().toString());params.put("leg",Integer.toString(intent.leg()));
        if(intent.command()==NpcCommand.PATROL){
            int[][] offsets={{intent.radius(),0},{0,intent.radius()},{-intent.radius(),0},{0,-intent.radius()}};
            int[] offset=offsets[Math.floorMod(intent.leg(),4)];var p=intent.anchor().offset(offset[0],0,offset[1]);
            if(!npc.level().hasChunkAt(p))return Optional.empty();
            // A global heightmap can select an overhead roof/bridge instead of this patrol floor.
            // Validate actual local footing; never count reaching the wrong vertical layer as arrival.
            BlockPos stand=null;
            for(int delta=0;delta<=4&&stand==null;delta++)for(int sign:new int[]{1,-1}){
                var candidate=p.offset(0,delta*sign,0);
                if(FieldworkRoutes.walkable(npc,candidate)){stand=candidate;break;}
            }
            if(stand==null){npc.reportWorkActivity("巡逻站位受阻",p,"附近没有真实可站立地面；命令到期后结束");return Optional.empty();}
            params.put("destination",Long.toString(stand.asLong()));
        }
        var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,params,NpcPlanStepStatus.PENDING,now,"命令任务起点");
        return Optional.of(new NpcPlan(UUID.randomUUID(),npc.getUUID(),"command:"+intent.id()+":"+intent.leg(),PROVIDER,
                NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,
                now,Math.min(intent.expires(),now+600),null,"有限命令段；威胁抢占后重新验证原命令"));
    }
    @Override public TaskTickResult start(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s){return tick(raw,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s){return tick(raw,p,s);}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s){
        var c=(NpcEntityExecutionContext)raw;var npc=c.npc();var data=NpcCommandData.get(npc.getServer());
        var intent=data.find(UUID.fromString(s.params().get("intent")));
        if(intent==null||intent.status()!=NpcCommandIntent.Status.ACTIVE||c.gameTime()>=intent.expires()
                ||!NpcCommandService.authorized(npc,intent,c.gameTime()))return failed(npc,p,"命令或原授权失效");
        int leg=Integer.parseInt(s.params().get("leg"));
        if(intent.leg()>leg){movement.remove(p.id());npc.getNavigation().stop();return TaskTickResult.stepSucceeded("命令段持久回执已存在，不重复到达结算");}
        BlockPos destination;
        switch(intent.command()) {
            case FOLLOW -> {
                var player=npc.getServer().getPlayerList().getPlayers().stream().filter(v->v.getUUID().equals(intent.player())).findFirst().orElse(null);
                if(player==null||!player.isAlive()||player.level()!=npc.level()
                        ||!intent.issuer().equals(CharacterRegistry.currentId(player)))
                    return failed(npc,p,"原发令人物离线、换身或不在本维度；不追随替代人物");
                destination=player.blockPosition();npc.getLookControl().setLookAt(player,20,20);
            }
            case STAY -> destination=intent.anchor();
            case PATROL -> destination=BlockPos.of(Long.parseLong(s.params().get("destination")));
            default -> {return failed(npc,p,"劳动由现有职业执行器完成");}
        }
        boolean near=npc.distanceToSqr(destination.getX()+.5,destination.getY(),destination.getZ()+.5)
                <=(intent.command()==NpcCommand.FOLLOW?9:2.25);
        if(near){
            npc.getNavigation().stop();movement.remove(p.id());
            if(intent.command()==NpcCommand.PATROL||c.gameTime()>=p.createdAt()+100){
                if(intent.leg()==leg)data.put(intent.update(NpcCommandIntent.Status.ACTIVE,leg+1,c.gameTime(),"真实到达，命令段完成"));
                return TaskTickResult.stepSucceeded("真实命令段完成，释放移动控制");
            }
            npc.reportWorkActivity(intent.command()==NpcCommand.FOLLOW?"随行安全等待":"有限驻守",destination,"授权有效且有明确到期时间");
            return TaskTickResult.running("已到达命令位置，有限观察");
        }
        if(!npc.level().hasChunkAt(destination))return failed(npc,p,"目标区块未加载；没有远程动作");
        var progress=movement.computeIfAbsent(p.id(),id->{var v=new MovementProgress();v.reset(npc,destination);return v;});
        if(progress.tick(npc,destination))return failed(npc,p,"路径持续无进展，结束当前命令段");
        if(npc.getNavigation().isDone()||npc.tickCount%20==0)FieldworkRoutes.move(npc,destination);
        npc.reportWorkActivity("执行有限"+intent.command(),destination,"分段真实移动，不使用旧跟随传送");
        return TaskTickResult.running("向命令目标移动");
    }
    private TaskTickResult failed(NpcBase npc,NpcPlan p,String detail){movement.remove(p.id());npc.getNavigation().stop();
        var data=NpcCommandData.get(npc.getServer());var i=data.find(UUID.fromString(p.currentStep().params().get("intent")));
        if(i!=null&&!i.closed()){data.put(i.update(NpcCommandIntent.Status.CANCELLED,i.leg(),npc.level().getGameTime(),detail));npc.setCommand(i.previous());}
        return TaskTickResult.failed(detail);
    }
    @Override public void cancel(NpcExecutionContext raw,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){movement.remove(p.id());if(raw instanceof NpcEntityExecutionContext c)c.npc().getNavigation().stop();}
}
