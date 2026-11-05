package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.settlement.SettlementMemberCommand;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** 普通查询不启用策略；启用必须由现任合法治理人物通过原权利检查。 */
public final class SettlementCoordinationCommand {
    private SettlementCoordinationCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("coordination")
                .then(Commands.literal("status").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var village = SettlementMemberCommand.nearest(player);
                    var data = SettlementCoordinatorData.existing(player.server);
                    var row = village == null || data == null ? null : data.site(village.buildingKey());
                    if (row == null) { player.sendSystemMessage(Component.literal("附近聚落尚未启用协作策略；查询不会启用。")); return 0; }
                    player.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                            "合法食物约 %.1f 日；农业%s；住宅 %d/57；另缺石料 %d。", row.getDouble("FoodDays"),
                            row.getBoolean("Harvest") ? "补库中" : "储备充足，允许协作",
                            SettlementCoordinatorData.job(row, "HOUSE").getInt("Placed"), row.getInt("StoneDeficit"))));
                    for (String key : row.getCompound("Jobs").getAllKeys()) {
                        var job = SettlementCoordinatorData.job(row, key);
                        player.sendSystemMessage(Component.literal(key + "：" + job.getString("State") + "；" + job.getString("Reason")));
                    }
                    return 1;
                }))
                .then(Commands.literal("safety-status").executes(context -> {
                    var player=context.getSource().getPlayerOrException();var village=SettlementMemberCommand.nearest(player);
                    var data=SettlementSafetyData.existing(player.server);var row=village==null||data==null?null:data.site(village.buildingKey());
                    if(row==null){player.sendSystemMessage(Component.literal("安全策略未启用；查询不自动建档。"));return 0;}
                    player.sendSystemMessage(Component.literal("已观测地面="+row.getInt("ObservedFloor")+"；暗点="+row.getInt("DarkFloor")));
                    for(String key:row.getCompound("Tasks").getAllKeys()) {var t=row.getCompound("Tasks").getCompound(key);player.sendSystemMessage(Component.literal(key+" "+t.getString("State")+" "+t.getString("Reason")));}
                    return 1;
                }))
                .then(Commands.literal("safety-activate").executes(context -> {
                    var player=context.getSource().getPlayerOrException();var village=SettlementMemberCommand.nearest(player);var person=CharacterRegistry.currentId(player);
                    if(village==null||person==null||!player.isAlive())return 0;
                    var result=SettlementSafetyData.get(player.server).activate(player.server,village.buildingKey(),person,player.serverLevel().getGameTime());
                    player.sendSystemMessage(Component.literal(result));return result.equals("ACTIVATED")||result.equals("ALREADY_ACTIVE")?1:0;
                }))
                .then(Commands.literal("activate").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var village = SettlementMemberCommand.nearest(player);
                    var person = CharacterRegistry.currentId(player);
                    if (village == null || person == null || !player.isAlive()) return 0;
                    String result = SettlementCoordinatorData.get(player.server).activate(player.server,
                            village.buildingKey(), person, player.serverLevel().getGameTime());
                    player.sendSystemMessage(Component.literal(result));
                    return result.equals("ACTIVATED") || result.equals("ALREADY_ACTIVE") ? 1 : 0;
                }))));
    }
}
