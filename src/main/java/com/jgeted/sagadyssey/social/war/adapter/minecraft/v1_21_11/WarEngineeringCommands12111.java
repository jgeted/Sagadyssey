package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.api.WarIntent;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Native command/media entry: finite geometry, particle preview and one-use exact confirmation. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarEngineeringCommands12111{
    @FunctionalInterface private interface Action{String run(ServerPlayer p)throws Exception;}
    @SubscribeEvent public static void register(RegisterCommandsEvent e){
        var root=Commands.literal("engineer").requires(s->s.getEntity() instanceof ServerPlayer).executes(c->run(c.getSource(),p->"工程：先 muster engineeroffer <军队> <兵团> 征得本人同意。build <军役UUID> <起点坐标> <终点坐标> <材料ID>；dig <军役UUID> <起点> <终点>。粒子预览后 confirm <预览UUID>，status <军队>；pause/resume/cancel/adopt <工程UUID>。spike_pit/web_snare <left|right> <军役> <起点> <终点>；tunnel <军役> <入口> <转点> <终点>；survey <军役> <站位> <起点> <终点>；cancelsurvey <军役>；hazards/caution <军队> [true|false]；disarm <军役> <观察UUID>。最多64步，原背包实物，逐工作面观察，未知不透视。"));
        root.then(Commands.literal("build").then(Commands.argument("service",UuidArgument.uuid()).then(Commands.argument("from",BlockPosArgument.blockPos()).then(Commands.argument("to",BlockPosArgument.blockPos()).then(Commands.argument("material",IdentifierArgument.id()).executes(c->preview(c.getSource(),UuidArgument.getUuid(c,"service"),WarEngineeringJob.Mode.BUILD,BlockPosArgument.getBlockPos(c,"from"),BlockPosArgument.getBlockPos(c,"to"),Optional.of(WarKey.parse(IdentifierArgument.getId(c,"material").toString())))))))));
        root.then(Commands.literal("dig").then(Commands.argument("service",UuidArgument.uuid()).then(Commands.argument("from",BlockPosArgument.blockPos()).then(Commands.argument("to",BlockPosArgument.blockPos()).executes(c->preview(c.getSource(),UuidArgument.getUuid(c,"service"),WarEngineeringJob.Mode.DIG,BlockPosArgument.getBlockPos(c,"from"),BlockPosArgument.getBlockPos(c,"to"),Optional.empty()))))));
        for(var kind:List.of(WarEngineeringProgram.Kind.SPIKE_PIT,WarEngineeringProgram.Kind.WEB_SNARE)){
            var trap=Commands.literal(kind.name().toLowerCase(Locale.ROOT));
            for(var side:List.of("left","right")){
                var target=Commands.argument("to",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),p->{
                    var layout=WarEngineeringLayouts.trap(kind,position(p,c,"from"),position(p,c,"to"),side.equals("left")?-1:1);
                    return showProgram(p,service(c),layout);
                }));
                trap.then(Commands.literal(side).then(Commands.argument("service",UuidArgument.uuid())
                        .then(Commands.argument("from",BlockPosArgument.blockPos()).then(target))));
            }
            root.then(trap);
        }
        var exit=Commands.argument("exit",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),p->{
            var layout=WarEngineeringLayouts.tunnel(position(p,c,"entrance"),position(p,c,"via"),position(p,c,"exit"));
            return showProgram(p,service(c),layout);
        }));
        root.then(Commands.literal("tunnel").then(Commands.argument("service",UuidArgument.uuid())
                .then(Commands.argument("entrance",BlockPosArgument.blockPos())
                .then(Commands.argument("via",BlockPosArgument.blockPos()).then(exit)))));
        var surveyEnd=Commands.argument("to",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),p->
                WarHazardRuntime.open(p.level().getServer()).survey(p,service(c),position(p,c,"stand"),position(p,c,"from"),position(p,c,"to"))));
        root.then(Commands.literal("survey").then(Commands.argument("service",UuidArgument.uuid())
                .then(Commands.argument("stand",BlockPosArgument.blockPos())
                .then(Commands.argument("from",BlockPosArgument.blockPos()).then(surveyEnd)))));
        root.then(Commands.literal("disarm").then(Commands.argument("service",UuidArgument.uuid()).then(Commands.argument("observation",UuidArgument.uuid()).executes(c->run(c.getSource(),p->{var r=WarEngineeringRuntime.open(p.level().getServer());return show(p,r,WarHazardRuntime.open(p.level().getServer()).disarm(p,UuidArgument.getUuid(c,"service"),UuidArgument.getUuid(c,"observation")));})))));
        root.then(Commands.literal("cancelsurvey").then(Commands.argument("service",UuidArgument.uuid()).executes(c->run(c.getSource(),p->WarHazardRuntime.open(p.level().getServer()).cancel(p,UuidArgument.getUuid(c,"service"))))));
        root.then(Commands.literal("hazards").then(Commands.argument("army",StringArgumentType.word()).executes(c->run(c.getSource(),p->String.join("\n",WarHazardRuntime.open(p.level().getServer()).status(p,army(p,StringArgumentType.getString(c,"army"))))))));
        root.then(Commands.literal("caution").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c->run(c.getSource(),p->WarHazardRuntime.open(p.level().getServer()).policy(p,army(p,StringArgumentType.getString(c,"army")),BoolArgumentType.getBool(c,"enabled")))))));
        root.then(Commands.literal("confirm").then(Commands.argument("preview",UuidArgument.uuid()).executes(c->run(c.getSource(),p->"有限工程已批准，原NPC须实际到场履职："+WarEngineeringRuntime.open(p.level().getServer()).confirm(p,UuidArgument.getUuid(c,"preview"))))));
        root.then(Commands.literal("status").then(Commands.argument("army",StringArgumentType.word()).executes(c->run(c.getSource(),p->{var m=WarMusterRuntime.existing(p.level().getServer()).orElseThrow();return String.join("\n",WarEngineeringRuntime.open(p.level().getServer()).status(p,WarMusterEntry.armyId(m.actor(p),StringArgumentType.getString(c,"army"))));}))));
        for(var action:List.of("pause","resume","cancel"))root.then(Commands.literal(action).then(Commands.argument("job",UuidArgument.uuid()).executes(c->run(c.getSource(),p->WarEngineeringRuntime.open(p.level().getServer()).control(p,UuidArgument.getUuid(c,"job"),action)))));
        root.then(Commands.literal("adopt").then(Commands.argument("job",UuidArgument.uuid()).executes(c->run(c.getSource(),p->WarEngineeringRuntime.open(p.level().getServer()).adopt(p,UuidArgument.getUuid(c,"job"))))));
        e.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(root)));
    }
    private static int preview(CommandSourceStack source,UUID service,WarEngineeringJob.Mode mode,net.minecraft.core.BlockPos a,net.minecraft.core.BlockPos b,Optional<WarKey> material){return run(source,p->{var r=WarEngineeringRuntime.open(p.level().getServer());var token=r.preview(p,service,mode,a,b,material);p.sendSystemMessage(Component.literal("[确认此工程] "+token).withStyle(s->s.withColor(0x7BCD9F).withClickEvent(new ClickEvent.RunCommand("/saga war engineer confirm "+token))));return r.previewText(token);});}
    private static String show(ServerPlayer p,WarEngineeringRuntime r,UUID token){p.sendSystemMessage(Component.literal("[确认此工程] "+token).withStyle(s->s.withColor(0x7BCD9F).withClickEvent(new ClickEvent.RunCommand("/saga war engineer confirm "+token))));return r.previewText(token);}
    private static String showProgram(ServerPlayer p,UUID service,WarEngineeringLayouts.Layout layout)throws Exception{
        var r=WarEngineeringRuntime.open(p.level().getServer());return show(p,r,r.previewProgram(p,service,layout));
    }
    private static UUID service(CommandContext<CommandSourceStack> context){return UuidArgument.getUuid(context,"service");}
    private static WarIntent.Position position(ServerPlayer p,CommandContext<CommandSourceStack> context,String label){
        var b=BlockPosArgument.getBlockPos(context,label);
        return new WarIntent.Position(WarKey.parse(p.level().dimension().identifier().toString()),b.getX(),b.getY(),b.getZ());
    }
    private static UUID army(ServerPlayer p,String name){return WarMusterEntry.armyId(WarMusterRuntime.existing(p.level().getServer()).orElseThrow().actor(p),name);}
    private static int run(CommandSourceStack s,Action a){try{for(var line:a.run(s.getPlayerOrException()).split("\n"))s.sendSuccess(()->Component.literal(line),false);return 1;}catch(Exception failure){s.sendFailure(Component.literal(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));return 0;}}
}
