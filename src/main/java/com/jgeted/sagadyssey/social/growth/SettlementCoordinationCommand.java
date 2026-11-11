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
                    var data = SettlementCoordinatorData.existing(player.level().getServer());
                    var row = village == null || data == null ? null : data.site(village.buildingKey());
                    if (row == null) { player.sendSystemMessage(Component.literal("附近聚落尚未启用协作策略；查询不会启用。")); return 0; }
                    player.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                            "合法食物约 %.1f 日；农业%s；住宅 %d/57；另缺石料 %d。", row.getDoubleOr("FoodDays", 0.0),
                            row.getBooleanOr("Harvest", false) ? "补库中" : "储备充足，允许协作",
                            SettlementCoordinatorData.job(row, "HOUSE").getIntOr("Placed", 0), row.getIntOr("StoneDeficit", 0))));
                    for (String key : row.getCompoundOrEmpty("Jobs").keySet()) {
                        var job = SettlementCoordinatorData.job(row, key);
                        player.sendSystemMessage(Component.literal(key + "：" + job.getStringOr("State", "") + "；" + job.getStringOr("Reason", "")));
                    }
                    return 1;
                }))
                .then(Commands.literal("safety-status").executes(context -> {
                    var player=context.getSource().getPlayerOrException();var village=SettlementMemberCommand.nearest(player);
                    var data=SettlementSafetyData.existing(player.level().getServer());var row=village==null||data==null?null:data.site(village.buildingKey());
                    if(row==null){player.sendSystemMessage(Component.literal("安全策略未启用；查询不自动建档。"));return 0;}
                    player.sendSystemMessage(Component.literal("已观测地面="+row.getIntOr("ObservedFloor", 0)+"；暗点="+row.getIntOr("DarkFloor", 0)));
                    for(String key:row.getCompoundOrEmpty("Tasks").keySet()) {var t=row.getCompoundOrEmpty("Tasks").getCompoundOrEmpty(key);player.sendSystemMessage(Component.literal(key+" "+t.getStringOr("State", "")+" "+t.getStringOr("Reason", "")));}
                    return 1;
                }))
                .then(Commands.literal("safety-activate").executes(context -> {
                    var player=context.getSource().getPlayerOrException();var village=SettlementMemberCommand.nearest(player);var person=CharacterRegistry.currentId(player);
                    if(village==null||person==null||!player.isAlive())return 0;
                    var result=SettlementSafetyData.get(player.level().getServer()).activate(player.level().getServer(),village.buildingKey(),person,player.level().getGameTime());
                    player.sendSystemMessage(Component.literal(result));return result.equals("ACTIVATED")||result.equals("ALREADY_ACTIVE")?1:0;
                }))
                .then(Commands.literal("activate").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var village = SettlementMemberCommand.nearest(player);
                    var person = CharacterRegistry.currentId(player);
                    if (village == null || person == null || !player.isAlive()) return 0;
                    String result = SettlementCoordinatorData.get(player.level().getServer()).activate(player.level().getServer(),
                            village.buildingKey(), person, player.level().getGameTime());
                    player.sendSystemMessage(Component.literal(result));
                    return result.equals("ACTIVATED") || result.equals("ALREADY_ACTIVE") ? 1 : 0;
                }))));
    }
}
