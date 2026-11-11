package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Permission-two truth and explicit tester knowledge reveal, never an ordinary player payload. */
@EventBusSubscriber(modid="sagadyssey")
public final class LifeBrainCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var brain=Commands.literal("brain").requires(s->MinecraftApi.hasPermission(s, 2));
        brain.then(Commands.literal("life").then(Commands.argument("target",EntityArgument.entity()).executes(c->{
            if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase npc))return 0;
            var data=LifeActivityData.get(c.getSource().getServer());
            c.getSource().sendSuccess(()->Component.literal("生活后台：退避至 "+data.retryAt(npc.getUUID())+"；已知地点 "+data.knownPlaces(npc.getUUID())),false);
            var life=com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(npc));var record=life.record(npc.getCharacterId());var needs=life.needs(npc.getCharacterId());
            if(record!=null)c.getSource().sendSuccess(()->Component.literal("后台需求：饥饿 "+(needs==null?"未登记":needs.hunger())+"；疲劳 "+record.fatigue()+"；私人床 "+record.bedPosition()+"；私人储物 "+record.privateStoragePosition()),false);
            var plans=new java.util.ArrayList<>(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()));
            com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).ifPresent(plans::add);
            for(var plan:plans)if(plan.providerId().equals(LifePlanFactory.PROVIDER)) {
                String key=plan.id()+":2",state=data.settlement(key);
                if(state!=null)c.getSource().sendSuccess(()->Component.literal("生活结算回执 "+key+"："+state),false);
            }
            for(var session:data.sessions())if(session.first().equals(npc.getUUID())||session.second().equals(npc.getUUID()))
                c.getSource().sendSuccess(()->Component.literal("会话 "+session.id()+" "+session.status()+"；地点 "+session.place()+"；开始 "+session.startedAt()+"；到期 "+session.expiresAt()+"；社会回执 "+data.settlement("social:"+session.id())),false);
            return 1;
        })));
        brain.then(Commands.literal("reveal-life-place").then(Commands.argument("target",EntityArgument.entity())
                .then(Commands.argument("activity",StringArgumentType.word()).then(Commands.argument("place",BlockPosArgument.blockPos()).executes(c->{
                    if(!(EntityArgument.getEntity(c,"target") instanceof NpcBase npc))return 0;
                    String activity=StringArgumentType.getString(c,"activity");
                    if(!VisitActivityRegistry.definitions().containsKey(net.minecraft.resources.Identifier.parse(activity)))return 0;
                    var pos=BlockPosArgument.getLoadedBlockPos(c,"place");
                    LifeActivityData.get(c.getSource().getServer()).reveal(npc.getUUID(),new LifeActivityData.Place(c.getSource().getLevel().dimension().identifier().toString(),pos,activity));
                    c.getSource().sendSuccess(()->Component.literal("测试者已明确告知此 NPC 活动地点；未赋予物品或私人资产权限"),false);return 1;
                })))));
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("npc").then(brain)));
    }
}
