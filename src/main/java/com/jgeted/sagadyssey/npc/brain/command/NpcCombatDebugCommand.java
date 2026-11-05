package com.jgeted.sagadyssey.npc.brain.command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
@EventBusSubscriber(modid="sagadyssey")
public final class NpcCombatDebugCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent e){var combat=Commands.literal("combat").requires(s->s.hasPermission(2));
        combat.then(Commands.literal("demo").executes(c->{try{String text=NpcCombatClientFixture.prepare(c.getSource().getPlayerOrException());c.getSource().sendSuccess(()->Component.literal(text),false);return 1;}catch(RuntimeException ex){c.getSource().sendFailure(Component.literal("集中夹具未完成："+ex.getMessage()));return 0;}}));
        combat.then(Commands.literal("demo-status").executes(c->{for(var text:NpcCombatClientFixture.status(c.getSource().getPlayerOrException()))c.getSource().sendSuccess(()->Component.literal(text),false);return 1;}));
        combat.then(Commands.literal("budget").executes(c->{var m=com.jgeted.sagadyssey.npc.brain.execution.NpcPlanningMetrics.snapshot(c.getSource().getServer());c.getSource().sendSuccess(()->Component.literal("规划耗时采样：次数="+m.calls()+" 最近P95="+m.p95Nanos()+"ns 最大="+m.maxNanos()+"ns 样本="+m.retained()+"/4096；不含整个实体AI/网络，也不是阶段10的百人预算验收"),false);return 1;}));
        e.getDispatcher().register(Commands.literal("saga").then(combat));}
}
