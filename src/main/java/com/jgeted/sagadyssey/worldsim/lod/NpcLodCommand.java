package com.jgeted.sagadyssey.worldsim.lod;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import java.util.*;

/** Backstage truth is OP-only. Never sent through ordinary NPC projections. */
public final class NpcLodCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("lod").requires(s->s.hasPermission(2))
                .executes(c->status(c.getSource())).then(Commands.literal("status").executes(c->status(c.getSource())))
                .then(Commands.literal("migration")
                    .then(Commands.literal("dryrun").then(Commands.argument("targets",EntityArgument.entities()).executes(c->{
                        var actors=targets(EntityArgument.getEntities(c,"targets"));var server=c.getSource().getServer();var digest=LodMigration.dryRun(server,actors);var counts=LodMigration.counts(server,actors);
                        c.getSource().sendSuccess(()->Component.literal("只读迁移检查（仅所选已加载原 NPC 及其持有物/关联订单，不含卸载仓库），counts="+counts+", revision="+digest+"；apply 前先备份完整世界；不复制物资、人物或订单。"),false);return 1;})))
                    .then(Commands.literal("report").then(Commands.argument("receipt",UuidArgument.uuid()).executes(c->{
                        var row=LodMigrationData.get(c.getSource().getServer()).find(UuidArgument.getUuid(c,"receipt"));if(row==null)throw new IllegalArgumentException("unknown migration receipt");
                        c.getSource().sendSuccess(()->Component.literal("迁移记录（所选原人物范围）="+row),false);return 1;})))
                    .then(Commands.literal("apply").then(Commands.argument("revision",StringArgumentType.word()).then(Commands.argument("targets",EntityArgument.entities()).executes(c->{
                        var id=LodMigration.apply(c.getSource().getServer(),targets(EntityArgument.getEntities(c,"targets")),StringArgumentType.getString(c,"revision"));
                        c.getSource().sendSuccess(()->Component.literal("迁移已保存，receipt="+id+"；原身体/库存/订单保留，新计划稍后公平调度。"),false);return 1;}))))
                    .then(Commands.literal("rollback").then(Commands.argument("receipt",UuidArgument.uuid()).executes(c->{LodMigration.rollback(c.getSource().getServer(),UuidArgument.getUuid(c,"receipt"));c.getSource().sendSuccess(()->Component.literal("未开始执行且资产修订未改变的迁移已回滚；未恢复任何旧物资副本。"),false);return 1;}))))));
    }
    public static int status(CommandSourceStack source) {
        var s=source.getServer();var data=NpcLodData.get(s);var budget=NpcLodBudgets.planning(s).snapshot();
        source.sendSuccess(()->Component.literal("LOD 检查点="+data.all().size()+", 已卸载="+data.all().stream().filter(LodCheckpoint::unloaded).count()+", 离线积压="+data.backlog(s.overworld().getGameTime())+", 需核对="+data.all().stream().filter(NpcLodRuntime::review).count()+"；规划 "+budget),false);
        source.sendSuccess(()->Component.literal("感知 "+NpcPerceptionQueue.snapshot(s)+"；寻路积压="+NpcNavigationQueue.backlog(s)+"；聚落积压="+com.jgeted.sagadyssey.social.economy.SettlementEconomyEvents.backlog(s)),false);
        for(var kind:LodMetrics.Kind.values())source.sendSuccess(()->Component.literal(kind+" "+LodMetrics.snapshot(s,kind)),false);
        source.sendSuccess(()->Component.literal(LodMetrics.payloads(s)+"；"+NpcLodBudgets.contextBytes(s).summary()),false);return 1;
    }
    private static List<NpcBase> targets(Collection<? extends net.minecraft.world.entity.Entity> entities) {
        if(entities.stream().anyMatch(e->!(e instanceof NpcBase)))throw new IllegalArgumentException("targets must contain NPCs only");
        return entities.stream().map(e->(NpcBase)e).toList();
    }
}
