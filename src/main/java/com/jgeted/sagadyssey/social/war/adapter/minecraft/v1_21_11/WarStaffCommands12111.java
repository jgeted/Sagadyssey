package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.*;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Additive player commands. All identities and authority are resolved on the server. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarStaffCommands12111 {
    private WarStaffCommands12111(){}
    @FunctionalInterface private interface Action{String run(ServerPlayer p)throws Exception;}
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("staff").requires(s->s.getEntity() instanceof ServerPlayer)
                .executes(c->run(c.getSource(),p->"先用 review <军队> 核定战斗编制，再用 status <军队> 查看可用岗位。appoint <军队> <岗位> <人物UUID> <任期天> <regular|acting|exceptional> <任用理由>。队长使用 unitappoint <军队> <兵团> <人物UUID> <天> <方式> <理由>。revoke <任命UUID> 撤职，advice <军队> 查战术建议，supplies <军队> 查粮饷。"))
                .then(Commands.literal("review").then(Commands.argument("army",StringArgumentType.word()).executes(c->run(c.getSource(),p->{var r=WarStaffRuntime.open(p.level().getServer()).review(p,army(p,StringArgumentType.getString(c,"army")));return reply(r); }))))
                .then(Commands.literal("status").then(Commands.argument("army",StringArgumentType.word()).executes(c->show(c.getSource(),StringArgumentType.getString(c,"army"),false))))
                .then(Commands.literal("advice").then(Commands.argument("army",StringArgumentType.word()).executes(c->show(c.getSource(),StringArgumentType.getString(c,"army"),true))))
                .then(Commands.literal("supplies").then(Commands.argument("army",StringArgumentType.word()).executes(c->run(c.getSource(),p->String.join("\n",WarStaffRuntime.open(p.level().getServer()).supplies(p,army(p,StringArgumentType.getString(c,"army"))))))))
                .then(Commands.literal("career").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("person",UuidArgument.uuid()).executes(c->run(c.getSource(),p->String.join("\n",WarStaffRuntime.open(p.level().getServer()).history(p,army(p,StringArgumentType.getString(c,"army")),UuidArgument.getUuid(c,"person"))))))))
                .then(Commands.literal("relay").then(Commands.argument("appointment",UuidArgument.uuid()).executes(c->run(c.getSource(),p->"已交办一次向所选兵团原集结旗集合；十秒内处理："+WarStaffRelayRuntime.open(p.level().getServer()).prepare(p,UuidArgument.getUuid(c,"appointment"))))))
                .then(Commands.literal("relaystatus").then(Commands.argument("task",UuidArgument.uuid()).executes(c->run(c.getSource(),p->WarStaffRelayRuntime.existing(p.level().getServer()).orElseThrow(()->new IllegalArgumentException("尚无委托记录")).status(p,UuidArgument.getUuid(c,"task"))))))
                .then(Commands.literal("revoke").then(Commands.argument("appointment",UuidArgument.uuid()).executes(c->run(c.getSource(),p->reply(WarStaffRuntime.open(p.level().getServer()).revoke(p,UuidArgument.getUuid(c,"appointment")))))));
        var appoint=Commands.literal("appoint").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("role",StringArgumentType.word())
                .suggests((c,b)->SharedSuggestionProvider.suggest(Arrays.stream(Role.values()).map(r->r.name().toLowerCase(Locale.ROOT)),b))
                .then(terms(false))));
        var unit=Commands.literal("unitappoint").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("unit",StringArgumentType.word()).then(terms(true))));
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(root.then(appoint).then(unit))));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,UUID> terms(boolean captain){
        return Commands.argument("person",UuidArgument.uuid()).then(Commands.argument("days",IntegerArgumentType.integer(1,30)).then(Commands.argument("mode",StringArgumentType.word())
                .suggests((c,b)->SharedSuggestionProvider.suggest(List.of("regular","acting","exceptional"),b))
                .then(Commands.argument("reason",StringArgumentType.greedyString()).executes(c->run(c.getSource(),p->{
                    var army=army(p,StringArgumentType.getString(c,"army"));var role=captain?Role.CAPTAIN:Role.valueOf(StringArgumentType.getString(c,"role").toUpperCase(Locale.ROOT));
                    var scope=captain?Optional.of(WarMusterEntry.unitId(army,StringArgumentType.getString(c,"unit"))):Optional.<UUID>empty();
                    return reply(WarStaffRuntime.open(p.level().getServer()).appoint(p,UUID.randomUUID(),army,role,scope,UuidArgument.getUuid(c,"person"),IntegerArgumentType.getInteger(c,"days"),Mode.valueOf(StringArgumentType.getString(c,"mode").toUpperCase(Locale.ROOT)),StringArgumentType.getString(c,"reason")));
                })))));
    }
    private static UUID army(ServerPlayer p,String name){var muster=WarMusterRuntime.existing(p.level().getServer()).orElseThrow(()->new IllegalArgumentException("尚未建立军队"));var actor=muster.actor(p);var id=WarMusterEntry.armyId(actor,name);if(!muster.book().owns(actor,id))throw new IllegalArgumentException("军队不属于当前人物");return id;}
    private static int show(CommandSourceStack source,String name,boolean advice){return run(source,p->{
        UUID army=army(p,name);var runtime=WarStaffRuntime.existing(p.level().getServer());
        if(runtime.isEmpty()){var failure=WarStaffRuntime.failure(p.level().getServer());if(!failure.isEmpty())throw new IllegalArgumentException(failure);return "尚无军职审核记录；领主仍可直接指挥，staff review 可开始审核。";}
        return String.join("\n",advice?runtime.get().advice(p,army):runtime.get().status(p,army));
    });}
    private static String reply(Reply r){if(r.status()!=Status.APPLIED&&r.status()!=Status.PENDING)throw new IllegalArgumentException(r.reason());return (r.status()==Status.PENDING?"正在保存，请用 staff status 查看实际状态":"记录已保存")+r.durable().map(v->" · "+v.id()).orElse("");}
    private static int run(CommandSourceStack source,Action action){try{var text=action.run(source.getPlayerOrException());for(var line:text.split("\n"))source.sendSuccess(()->Component.literal(line),false);return 1;}catch(Exception e){source.sendFailure(Component.literal(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));return 0;}}
}
