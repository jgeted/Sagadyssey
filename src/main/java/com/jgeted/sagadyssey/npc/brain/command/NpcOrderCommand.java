package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Ordinary intent issuance is permission-scoped; full causal truth is a separate OP2 view. */
@EventBusSubscriber(modid="sagadyssey")
public final class NpcOrderCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent e){
        var npc=Commands.literal("npc");
        npc.then(Commands.literal("withdraw").then(Commands.argument("target",EntityArgument.entity()).executes(c->{
            var player=c.getSource().getPlayerOrException();if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase n)||player.distanceToSqr(n)>64)return 0;
            if(!NpcDutyTask.requestWithdrawal(player,n)){c.getSource().sendFailure(Component.literal("没有原服务关系允许的有限撤离权限，或人物模式不适用"));return 0;}
            c.getSource().sendSuccess(()->Component.literal("已登记有限紧急撤离；安全边界暂停工作，不授予攻击或私人库存权利"),false);return 1;
        })));
        npc.then(Commands.literal("order").then(Commands.argument("target",EntityArgument.entity())
                .then(Commands.argument("command",StringArgumentType.word()).then(Commands.argument("ticks",IntegerArgumentType.integer(20,24000)).executes(c->{
                    var player=c.getSource().getPlayerOrException();
                    if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase n)||player.distanceToSqr(n)>64){c.getSource().sendFailure(Component.literal("请靠近要协商命令的 NPC"));return 0;}
                    NpcCommand command;try{command=NpcCommand.valueOf(StringArgumentType.getString(c,"command").toUpperCase(java.util.Locale.ROOT));}
                    catch(IllegalArgumentException x){c.getSource().sendFailure(Component.literal("命令：FOLLOW / STAY / WORK / PATROL"));return 0;}
                    var anchor=command==NpcCommand.PATROL&&n.getPatrolCenter()!=null?n.getPatrolCenter():n.blockPosition();
                    var result=NpcCommandService.issue(player,n,command,anchor,Math.max(4,n.getPatrolRadius()),IntegerArgumentType.getInteger(c,"ticks"));
                    if(!result.accepted()){c.getSource().sendFailure(Component.literal(result.detail()));return 0;}
                    c.getSource().sendSuccess(()->Component.literal(result.detail()),false);return 1;
                })))));
        npc.then(Commands.literal("order-status").then(Commands.argument("target",EntityArgument.entity()).executes(c->{
            var player=c.getSource().getPlayerOrException();if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase n))return 0;
            var issuer=CharacterRegistry.ensureCurrent(player).id();
            var own=NpcCommandData.get(player.server).known(issuer,n.getCharacterId());
            if(own.isEmpty())c.getSource().sendSuccess(()->Component.literal("没有你本人可确认的命令记录"),false);
            for(var i:own)c.getSource().sendSuccess(()->Component.literal("本人命令 "+i.command()+"："+i.status()+"；到期 "+i.expires()+"；"+i.detail()),false);
            return 1;
        })));
        npc.then(Commands.literal("brain").requires(s->s.hasPermission(2)).then(Commands.literal("commands")
                .then(Commands.argument("target",EntityArgument.entity()).executes(c->{
                    if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase n))return 0;
                    for(var i:NpcCommandData.get(c.getSource().getServer()).all())if(i.character().equals(n.getCharacterId()))
                        c.getSource().sendSuccess(()->Component.literal("命令后台 "+i.id()+" 发令人物="+i.issuer()+" 原授权="+i.evidence()+" 范围="+i.dimension()+"/"+i.anchor()+"/"+i.radius()+" 进度="+i.leg()+" "+i.status()),false);
                    return 1;
                }))));
        npc.then(Commands.literal("brain").requires(s->s.hasPermission(2)).then(Commands.literal("duties").then(Commands.argument("target",EntityArgument.entity()).executes(c->{
            if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase n))return 0;
            for(var d:NpcDutyData.get(n.getServer()).all())if(d.character().equals(n.getCharacterId()))c.getSource().sendSuccess(()->Component.literal("职责后台 "+d.kind()+" "+d.id()+" 对象="+d.targetCharacter()+" 来源="+d.source()+" 原证明="+d.proofs()+" 实际="+d.worked()+"/"+d.duration()+" "+d.status()+" "+d.detail()),false);
            for(var r:NpcInterruptionData.get(n.getServer()).all())if(r.character().equals(n.getCharacterId()))c.getSource().sendSuccess(()->Component.literal("抢占后台 原计划="+r.plan()+" 义务="+r.candidate()+" 原因="+r.reason()+" 续接="+r.continuation()),false);
            return 1;
        }))));
        e.getDispatcher().register(Commands.literal("saga").then(npc));
    }
}
