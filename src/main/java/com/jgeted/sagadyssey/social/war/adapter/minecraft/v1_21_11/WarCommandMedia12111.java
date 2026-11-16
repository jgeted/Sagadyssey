package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarDispatchService;
import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.*;
import java.util.*;

/** Vanilla map ground clicks, actual fixed banners and native horn use; civilian command wand stays independent. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarCommandMedia12111 {
    private WarCommandMedia12111(){}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        var war=Commands.literal("war").requires(s->s.getEntity() instanceof ServerPlayer)
                .executes(c->{tell(c.getSource().getPlayerOrException(),"手持已绘制地图潜行朝空中右键，打开军令地图和军籍簿。面对原 NPC 潜行右键可商议军务。");return 1;})
                .then(Commands.literal("map").executes(c->WarMapNetwork12111.open(c.getSource().getPlayerOrException())?1:0))
                .then(Commands.literal("list").executes(c->{var p=c.getSource().getPlayerOrException();var m=open(p);if(m.isEmpty())return unavailable(p);var units=m.get().units(p);if(units.isEmpty())tell(p,"当前没有可指挥的已编组兵团。");for(var u:units)tell(p,u.id()+" · "+u.troopType());return units.size();}))
                .then(Commands.literal("select").then(Commands.argument("unit",UuidArgument.uuid()).executes(c->{var p=c.getSource().getPlayerOrException();boolean ok=open(p).filter(m->m.select(p,UuidArgument.getUuid(c,"unit"))).isPresent();tell(p,ok?"已选择兵团；地图点地将下达行军令。":"无法选择该兵团：需要当前人物的军队指挥权。");return ok?1:0;})))
                .then(Commands.literal("clear").executes(c->{var p=c.getSource().getPlayerOrException();existing(p).ifPresent(m->m.clear(p));tell(p,"已取消军事选择。");return 1;}))
                .then(Commands.literal("report").executes(c->{var p=c.getSource().getPlayerOrException();var m=existing(p);if(m.isEmpty()||m.get().selection(p).isEmpty())return unavailable(p);var reports=m.get().reports(p);tell(p,"所选兵团收到的己方报告："+reports.size()+" 人");long now=p.level().getGameTime();for(var r:reports)tell(p,r.character()+" · "+r.state()+" · "+r.position().x()+", "+r.position().y()+", "+r.position().z()+" · "+Math.max(0,now-r.observedAt())+" 刻前");return 1;}));
        var modes=Commands.literal("mode");
        for(var e:Map.of("move",InitialWarDefinitions.MOVE,"hold",InitialWarDefinitions.HOLD,"retreat",InitialWarDefinitions.RETREAT,"rally",InitialWarDefinitions.RALLY,"charge",InitialWarDefinitions.CHARGE).entrySet())modes.then(Commands.literal(e.getKey()).executes(c->{var p=c.getSource().getPlayerOrException();boolean ok=existing(p).filter(m->m.mode(p,e.getValue())).isPresent();tell(p,ok?"已切换地图军令："+e.getKey():"请先选择有权指挥的兵团。");return ok?1:0;}));
        war.then(modes);event.getDispatcher().register(Commands.literal("saga").then(war));
    }
    @SubscribeEvent(priority=EventPriority.LOW) public static void map(PlayerInteractEvent.RightClickBlock e){
        if(!(e.getEntity() instanceof ServerPlayer p) || e.getHand()!=InteractionHand.MAIN_HAND || !p.getMainHandItem().is(Items.FILLED_MAP) || p.isSpectator() || net.minecraft.world.item.MapItem.getSavedData(p.getMainHandItem(),p.level())==null)return;
        if (WarManagementRuntime.awaitsBlock(p)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.CONSUME);
            if (realClick(p, e.getHitVec())) WarManagementNetwork12111.block(p, e.getPos(), e.getFace());
            else tell(p, "请在实际可见、伸手可达的方块上标记。");
            return;
        }
        var m=existing(p);if(m.isEmpty() || m.get().selection(p).isEmpty())return;
        var hit=e.getHitVec();if(!realClick(p,hit)){tell(p,"只能在视线和触及范围内指定位置。");e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;}
        var state=p.level().getBlockState(e.getPos());
        if(state.getBlock() instanceof AbstractBannerBlock){
            var reply=m.get().bindPost(p,position(p,e.getPos()),WarKey.parse(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
            tell(p,reply.status()==WarCommandPostPorts.Status.APPLIED?"已登记集结旗。":reply.status()==WarCommandPostPorts.Status.PENDING?"正在登记集结旗，完成后可吹角集结。":"暂时无法登记集结旗。");
            // Let the same actual map keep its vanilla banner marker behavior.
            return;
        }
        if (p.level().getBlockEntity(e.getPos()) instanceof Container) return;
        if(hit.getDirection()!=Direction.UP){tell(p,"请在地面上指定落脚位置。");e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;}
        var result=m.get().issue(p,position(p,e.getPos().above()),false);orderMessage(p,result);e.setCanceled(true);e.setCancellationResult(InteractionResult.CONSUME);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void horn(PlayerInteractEvent.RightClickItem e){
        if(!(e.getEntity() instanceof ServerPlayer p) || e.getHand()!=InteractionHand.MAIN_HAND || !p.getMainHandItem().is(Items.GOAT_HORN) || p.isSpectator())return;
        var m=existing(p);if(m.isEmpty() || m.get().selection(p).isEmpty())return;var stack=p.getMainHandItem();
        if(p.getCooldowns().isOnCooldown(stack) || stack.getItem().getUseDuration(stack,p)<=0)return;
        var post=m.get().post(p);
        if(post.isEmpty() || !actualPost(p,post.get()) || !m.get().maySignal(p)){tell(p,"号令未送达：需要仍在原处的集结旗，以及附近兵团的新近报告。");return;}
        // Execute the vanilla instrument once. Only its successful use can issue the local signal.
        var used=stack.use(p.level(),p,e.getHand());e.setCanceled(true);e.setCancellationResult(used);
        if(used.consumesAction())orderMessage(p,m.get().issue(p,post.get().position(),true));
    }
    @SubscribeEvent public static void mapPanel(PlayerInteractEvent.RightClickItem e){
        if(e.getEntity() instanceof ServerPlayer p && e.getHand()==InteractionHand.MAIN_HAND && p.isShiftKeyDown() && p.getMainHandItem().is(Items.FILLED_MAP)){
            boolean opened=WarMapNetwork12111.open(p);e.setCanceled(true);e.setCancellationResult(opened?InteractionResult.CONSUME:InteractionResult.FAIL);
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p)existing(p).ifPresent(m->m.clear(p));}
    public static boolean actualPost(ServerPlayer p,WarCommandPost post){
        var point=post.position();if(!point.dimension().toString().equals(p.level().dimension().identifier().toString()))return false;
        var at=new BlockPos(point.x(),point.y(),point.z());if(p.level().getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)==null)return false;var state=p.level().getBlockState(at);
        return state.getBlock() instanceof AbstractBannerBlock && BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(post.block().toString());
    }
    private static boolean realClick(ServerPlayer p,BlockHitResult hit){
        if(p.getEyePosition().distanceToSqr(hit.getLocation())>Math.pow(p.blockInteractionRange()+.25,2))return false;
        var actual=p.level().clip(new ClipContext(p.getEyePosition(),hit.getLocation().add(hit.getDirection().getUnitVec3().scale(-.01)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        return actual.getType()==HitResult.Type.BLOCK && actual.getBlockPos().equals(hit.getBlockPos());
    }
    private static Optional<WarCommandMediaRuntime> existing(ServerPlayer p){return WarServerEvents.existing(p.level().getServer()).flatMap(WarServerRuntime::commandMedia);}
    private static Optional<WarCommandMediaRuntime> open(ServerPlayer p){return WarServerEvents.existing(p.level().getServer()).flatMap(WarServerRuntime::openCommandMedia);}
    private static WarIntent.Position position(ServerPlayer p,BlockPos at){return new WarIntent.Position(WarKey.parse(p.level().dimension().identifier().toString()),at.getX(),at.getY(),at.getZ());}
    private static int unavailable(ServerPlayer p){tell(p,"军事指挥尚未就绪，或尚未选择可指挥的兵团。");return 0;}
    private static void orderMessage(ServerPlayer p,WarDispatchService.Result result){
        if(result.reason().equals("SPEAR_OR_MOUNTED_SWORD_ROSTER_REQUIRED_FOR_CHARGE")){tell(p,"突击需要已整备的长矛兵团或骑乘剑士兵团。");return;}
        if(result.reason().equals("CHARGE_APPROACH_REQUIRES_8_TO_48_BLOCKS")){tell(p,"突击目标须距集结点8至48格，高差不超过4格。");return;}
        tell(p,result.outcome()==WarDispatchService.Outcome.ACCEPTED?"军令已发出；"+result.record().map(t->"预计 "+t.route().delayTicks()+" 刻后送达。").orElse(""):"军令未发出，请检查指挥权限、兵团状态和己方报告。");
    }
    private static void tell(ServerPlayer p,String message){p.displayClientMessage(Component.literal(message),false);}
}
