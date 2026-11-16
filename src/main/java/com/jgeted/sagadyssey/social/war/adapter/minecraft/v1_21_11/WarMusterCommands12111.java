package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarMusterRuntime;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Additive military commands; no OP privilege, civilian networking change or raw entity-id possession. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarMusterCommands12111 {
    private static final Map<String,WarKey> PROFILES=profiles();
    private WarMusterCommands12111(){}
    @FunctionalInterface private interface Action { WarMusterRuntime.Result run(ServerPlayer player)throws Exception; }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("muster").requires(s->s.getEntity() instanceof ServerPlayer)
                .executes(c->{c.getSource().sendSuccess(()->Component.literal("小队整备：create <军队名>；unit <军队名> <兵团名> <配装>；profiles；面向NPC offer <军队名> <兵团名> [每日军饷] [天数]；confirm <提议UUID>；status；retire <军役UUID>。先通过原递交入口备齐NPC自有装具和食物。"),false);return 1;})
                .then(Commands.literal("status").executes(c->status(c.getSource())))
                .then(Commands.literal("profiles").executes(c->{c.getSource().sendSuccess(()->Component.literal("基础配装：sword（无盾无甲剑士）、shield（有盾有甲剑士）、bow、crossbow、light_cavalry；pike、heavy_cavalry 接原版长矛；工程军役用 engineeroffer 单独征得本人同意。其他组合可用配装参数自动补全。"),false);return 1;}))
                .then(Commands.literal("create").then(Commands.argument("army",StringArgumentType.word()).executes(c->execute(c.getSource(),p->WarMusterRuntime.open(p.level().getServer()).createArmy(p,StringArgumentType.getString(c,"army"))))))
                .then(Commands.literal("unit").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("name",StringArgumentType.word()).then(Commands.argument("profile",StringArgumentType.word())
                        .suggests((c,b)->SharedSuggestionProvider.suggest(PROFILES.keySet(),b))
                        .executes(c->execute(c.getSource(),p->{var profile=PROFILES.get(StringArgumentType.getString(c,"profile"));if(profile==null)throw new IllegalArgumentException("未知配装，请用自动补全");
                            return WarMusterRuntime.open(p.level().getServer()).createUnit(p,StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"name"),profile);}))))))
                .then(Commands.literal("offer").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("unit",StringArgumentType.word())
                        .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),1,1))
                        .then(Commands.argument("wage",IntegerArgumentType.integer(1,64))
                                .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),1))
                                .then(Commands.argument("days",IntegerArgumentType.integer(1,30)).executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),IntegerArgumentType.getInteger(c,"days"))))))))
                .then(Commands.literal("staffoffer").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("unit",StringArgumentType.word())
                        .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),1,1,true))
                        .then(Commands.argument("wage",IntegerArgumentType.integer(1,64))
                                .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),1,true))
                                .then(Commands.argument("days",IntegerArgumentType.integer(1,30)).executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),IntegerArgumentType.getInteger(c,"days"),true)))))))
                .then(Commands.literal("engineeroffer").then(Commands.argument("army",StringArgumentType.word()).then(Commands.argument("unit",StringArgumentType.word())
                        .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),1,1,WarMilitiaRequest.Role.ENGINEER))
                        .then(Commands.argument("wage",IntegerArgumentType.integer(1,64))
                                .executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),1,WarMilitiaRequest.Role.ENGINEER))
                                .then(Commands.argument("days",IntegerArgumentType.integer(1,30)).executes(c->offer(c.getSource(),StringArgumentType.getString(c,"army"),StringArgumentType.getString(c,"unit"),IntegerArgumentType.getInteger(c,"wage"),IntegerArgumentType.getInteger(c,"days"),WarMilitiaRequest.Role.ENGINEER)))))))
                .then(Commands.literal("confirm").then(Commands.argument("ticket",UuidArgument.uuid()).executes(c->execute(c.getSource(),p->WarMusterRuntime.open(p.level().getServer()).confirm(p,UuidArgument.getUuid(c,"ticket"))))))
                .then(Commands.literal("retire").then(Commands.argument("service",UuidArgument.uuid()).executes(c->execute(c.getSource(),p->WarMusterRuntime.open(p.level().getServer()).retire(p,UuidArgument.getUuid(c,"service"))))));
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(root)));
    }
    private static int offer(CommandSourceStack source,String army,String unit,int wage,int days){
        return offer(source,army,unit,wage,days,false);
    }
    private static int offer(CommandSourceStack source,String army,String unit,int wage,int days,boolean staff){
        return offer(source,army,unit,wage,days,staff?WarMilitiaRequest.Role.STAFF:WarMilitiaRequest.Role.COMBATANT);
    }
    private static int offer(CommandSourceStack source,String army,String unit,int wage,int days,WarMilitiaRequest.Role role){
        boolean staff=role==WarMilitiaRequest.Role.STAFF;
        try{
            var p=source.getPlayerOrException();var start=p.getEyePosition();var end=start.add(p.getViewVector(1).scale(6));
            var hit=ProjectileUtil.getEntityHitResult(p,start,end,p.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),e->e instanceof NpcBase&&e.isAlive(),36);
            if(hit==null||!(hit.getEntity() instanceof NpcBase npc))throw new IllegalArgumentException("请面向六格内可见的NPC");
            var r=WarMusterRuntime.open(p.level().getServer());var terms=r.propose(p,npc,army,unit,wage,days,role);
            p.sendSystemMessage(Component.literal(npc.getName().getString()+(role==WarMilitiaRequest.Role.ENGINEER?"：工程军役（按逐项批准的有限工程实际施工，材料/工具/产物依原物权，不参与战斗收令），每日 ":staff?"：专职军务勤务（整理本军报告，不参与战斗收令），每日 ":"：战斗军役，每日 ")+wage+" 绿宝石，军役至世界时刻 "+terms.endsAt()+"；仅本维度危险职责，自备装具、食物按原真实背包消耗。玩家背包离线时可能形成欠饷。NPC将自行决定是否接受。请在两分钟内确认，届时仍会重新核验条款。"));
            if(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.LOADOUTS.find(terms.request().loadout()).orElseThrow().mount()!=WarLoadout.Mount.FOOT)p.sendSystemMessage(Component.literal("坐骑每12000刻需从骑手原背包实际使用一份干草块；缺料暂停骑战，原军饷继续计提。"));
            p.sendSystemMessage(Component.literal("[确认本次条款] "+terms.ticket()).withStyle(s->s.withColor(0x7BCD9F).withClickEvent(new ClickEvent.RunCommand("/saga war muster confirm "+terms.ticket()))));return 1;
        }catch(Exception invalid){source.sendFailure(Component.literal(message(invalid)));return 0;}
    }
    private static int execute(CommandSourceStack source,Action action){
        try{var result=action.run(source.getPlayerOrException());if(result.accepted())source.sendSuccess(()->Component.literal(result.message()),false);else source.sendFailure(Component.literal(result.message()));return result.accepted()?1:0;}
        catch(Exception invalid){source.sendFailure(Component.literal(message(invalid)));return 0;}
    }
    private static int status(CommandSourceStack source){
        try{var p=source.getPlayerOrException();var r=WarMusterRuntime.existing(p.level().getServer());
            if(r.isEmpty()){String failure=WarMusterRuntime.failure(p.level().getServer());source.sendSuccess(()->Component.literal(failure.isEmpty()?"尚未建立军队。用 /saga war muster create <军队名> 开始。":failure),false);return 1;}
            String status=r.get().status(r.get().actor(p));for(String line:status.split("\n"))p.sendSystemMessage(Component.literal(line));return 1;
        }catch(Exception invalid){source.sendFailure(Component.literal(message(invalid)));return 0;}
    }
    private static String message(Exception e){return e.getMessage()==null?"操作暂不可用："+e.getClass().getSimpleName():e.getMessage();}
    private static Map<String,WarKey> profiles(){
        var m=new LinkedHashMap<String,WarKey>();m.put("sword",new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false).id());
        m.put("shield",new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,true,true).id());
        m.put("pike",new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SPEAR,false,false).id());
        m.put("bow",new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.BOW,false,false).id());m.put("crossbow",new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.CROSSBOW,false,false).id());
        m.put("light_cavalry",new WarLoadout(WarLoadout.Mount.LIGHT,WarLoadout.Weapon.SWORD,false,false).id());m.put("heavy_cavalry",new WarLoadout(WarLoadout.Mount.HEAVY,WarLoadout.Weapon.SPEAR,true,true).id());
        for(var key:InitialWarDefinitions.LOADOUTS.profiles().keySet())m.put(key.path().replace("war/loadout/","").replace("/","__"),key);return Collections.unmodifiableMap(m);
    }
}
