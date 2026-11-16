package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

@EventBusSubscriber(modid="sagadyssey")
public final class WarSupportCommands12111 {
    @FunctionalInterface private interface Action {String run(ServerPlayer p)throws Exception;}
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("support").requires(s->s.getEntity() instanceof ServerPlayer).executes(c->run(c.getSource(),p->"保障：depot <军队> <箱子坐标>；status <军队>；ship <承运军役UUID> <目的库坐标> <物品ID> <每趟数量> <趟数>；supply <承运军役UUID> <收货人物UUID> <物品ID> <数量> <趟数>；cancel <运单UUID>；allowance <任命UUID> <每日津贴>。运输限同维度128格，1至8趟；货物真实领退。"))
            .then(Commands.literal("depot").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),p->WarCampaignRuntime.open(p.level().getServer()).bindDepot(p,army(p,StringArgumentType.getString(c,"army")),BlockPosArgument.getBlockPos(c,"pos")))))))
            .then(Commands.literal("status").then(Commands.argument("army",StringArgumentType.word()).executes(c->run(c.getSource(),p->String.join("\n",WarCampaignRuntime.open(p.level().getServer()).status(p,army(p,StringArgumentType.getString(c,"army"))))))))
            .then(Commands.literal("cancel").then(Commands.argument("task",UuidArgument.uuid()).executes(c->run(c.getSource(),p->WarCampaignRuntime.open(p.level().getServer()).cancel(p,UuidArgument.getUuid(c,"task"),false)))))
            .then(Commands.literal("allowance").then(Commands.argument("appointment",UuidArgument.uuid()).then(Commands.argument("daily",IntegerArgumentType.integer(1,16)).executes(c->run(c.getSource(),p->WarCampaignRuntime.open(p.level().getServer()).allowance(p,UuidArgument.getUuid(c,"appointment"),IntegerArgumentType.getInteger(c,"daily")))))));
        var ship=Commands.literal("ship").then(Commands.argument("service",UuidArgument.uuid()).then(Commands.argument("pos",BlockPosArgument.blockPos()).then(terms(false))));
        var supply=Commands.literal("supply").then(Commands.argument("service",UuidArgument.uuid()).then(Commands.argument("person",UuidArgument.uuid()).then(terms(true))));
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(root.then(ship).then(supply))));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,net.minecraft.resources.Identifier> terms(boolean personal){
        return Commands.argument("item",IdentifierArgument.id()).then(Commands.argument("quantity",IntegerArgumentType.integer(1,64)).then(Commands.argument("trips",IntegerArgumentType.integer(1,8)).executes(c->run(c.getSource(),p->{
            ItemLocation target;if(personal){var person=UuidArgument.getUuid(c,"person");var body=CharacterRegistryData.get(p.level().getServer()).bodyOf(person);if(body==null)throw new IllegalArgumentException("原收货人物身体未知");target=ItemLocation.personal(person,body.entityId());}
            else target=WarCampaignRuntime.destination(p,BlockPosArgument.getBlockPos(c,"pos"));
            return "有界运输已批准，货物须实际送达："+WarCampaignRuntime.open(p.level().getServer()).ship(p,UuidArgument.getUuid(c,"service"),target,IdentifierArgument.getId(c,"item").toString(),IntegerArgumentType.getInteger(c,"quantity"),IntegerArgumentType.getInteger(c,"trips"));
        }))));
    }
    private static UUID army(ServerPlayer p,String name){var m=WarMusterRuntime.existing(p.level().getServer()).orElseThrow();var id=WarMusterEntry.armyId(m.actor(p),name);if(!m.book().owns(m.actor(p),id))throw new IllegalArgumentException("军队不属于当前人物");return id;}
    private static int run(CommandSourceStack s,Action a){try{for(var line:a.run(s.getPlayerOrException()).split("\n"))s.sendSuccess(()->Component.literal(line),false);return 1;}catch(Exception e){s.sendFailure(Component.literal(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));return 0;}}
}
