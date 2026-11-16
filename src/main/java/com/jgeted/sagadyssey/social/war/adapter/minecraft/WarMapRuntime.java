package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Read-only map projections and explicit preview/commit input. Never creates armies, consents or military claims. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarMapRuntime {
    private static final Map<MinecraftServer,WarMapRuntime> RUNTIMES=new HashMap<>();
    private final MinecraftServer server; private final WarMapService service=new WarMapService();
    private WarMapRuntime(MinecraftServer server){this.server=server;}
    public static WarMapRuntime get(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("map requires server thread");return RUNTIMES.computeIfAbsent(server,WarMapRuntime::new);}
    public Update begin(ServerPlayer p){
        check();var actor=CharacterRegistry.currentId(p);var actual=sheet(p);
        if(actor==null||actual.isEmpty()||p.isSpectator()||!p.isAlive()||p.level().getServer()!=server)throw new IllegalArgumentException("current held map required");
        return snapshot(p,service.open(p.getUUID(),actor,actual.get(),now()),0,true,"选择兵团，再点击地图预览目标。");
    }
    /** Same entry used by bounded network packets and native integration tests. */
    public Update act(ServerPlayer p,Request request){
        check();if(p.level().getServer()!=server)throw new IllegalArgumentException("foreign server player");var actor=CharacterRegistry.currentId(p);var found=actor==null?Optional.<WarMapService.Lease>empty():service.find(p.getUUID(),actor,request.session(),now());
        if(found.isEmpty())return Update.closed(request.session(),"地图会话已失效，请重新打开。");
        var lease=found.get();if(request.action()==Action.CLOSE){service.close(lease);return Update.closed(lease.id(),"");}
        if(p.isSpectator() || !p.isAlive() || sheet(p).filter(lease.sheet()::equals).isEmpty()){service.close(lease);return Update.closed(lease.id(),"手持地图或当前人物已改变，请重新打开。");}
        boolean replay=request.action()==Action.ISSUE && service.receipt(lease,request.ticket()).isPresent();
        if(!replay && request.revision()!=lease.revision())return snapshot(p,lease,request.page(),false,"界面已更新，请重新选择目标。");
        try{
            if(request.action()==Action.REFRESH)return snapshot(p,service.refresh(lease,now()),request.page(),false,"");
            if(request.action()==Action.SELECT_FIELD){
                var media=WarServerEvents.existing(server).flatMap(WarServerRuntime::openCommandMedia)
                        .orElseThrow(()->new IllegalArgumentException("ARMY_UNAVAILABLE"));
                if(!media.select(p,request.unit()))throw new IllegalArgumentException("ARMY_AUTHORITY_REQUIRED");
                media.mode(p,kind(request.mode()));
                return snapshot(p,service.refresh(lease,now()),request.page(),false,"已选为现场兵团。持地图登记战旗，附近可吹角集结。");
            }
            if(request.action()==Action.PREVIEW){
                var core=WarServerEvents.existing(server).orElseThrow(()->new IllegalArgumentException("ARMY_UNAVAILABLE"));
                var unit=core.session().flatMap(s->s.unit(request.unit())).orElseThrow(()->new IllegalArgumentException("ARMY_UNAVAILABLE"));
                if(core.permission(actor,unit.armyId())!=WarCommandService.Permission.ALLOWED)throw new IllegalArgumentException("ARMY_AUTHORITY_REQUIRED");
                var target=target(p,lease.sheet(),request.pixelX(),request.pixelZ());
                var route=core.field().flatMap(f->f.route(actor,unit.armyId(),unit.id())).orElseThrow(()->new IllegalArgumentException("FRESH_OWN_REPORT_REQUIRED"));
                var command=new WarCommand.IssueOrder(UUID.randomUUID(),core.session().orElseThrow().generation(),actor,unit.armyId(),unit.id(),unit.revision(),new WarIntent(kind(request.mode()),1,target));
                var next=service.refresh(lease,now());service.prepare(next,command,request.mode(),WarMapView.Delivery.valueOf(route.channel().name()),route.delayTicks(),now());
                return snapshot(p,next,request.page(),false,"目标已预览；确认后才会发令。");
            }
            if(request.action()==Action.ISSUE){
                var receipt=service.issue(lease,request.ticket(),now(),command->{
                    var core=WarServerEvents.existing(server).orElseThrow(()->new IllegalArgumentException("ARMY_UNAVAILABLE"));
                    if(core.permission(actor,command.armyId())!=WarCommandService.Permission.ALLOWED)return WarMapView.Receipt.rejected("ARMY_AUTHORITY_REQUIRED");
                    // Pixel and height are resolved again from the same actual held map; never use supplied world coordinates.
                    var preview=command.intent().target();int px=(int)Math.floor(lease.sheet().pixelX(preview.x())),pz=(int)Math.floor(lease.sheet().pixelZ(preview.z()));
                    if(!target(p,lease.sheet(),px,pz).equals(preview))return WarMapView.Receipt.rejected("TARGET_CHANGED");
                    var result=core.openCommandMedia().orElseThrow(()->new IllegalArgumentException("ARMY_UNAVAILABLE")).issuePrepared(p,command);
                    if(result.outcome()==WarDispatchService.Outcome.ACCEPTED){core.commandMedia().ifPresent(m->{m.select(p,command.unitId());m.mode(p,command.intent().kind());});return new WarMapView.Receipt(WarMapView.Outcome.ACCEPTED,"ORDER_ACCEPTED",Optional.of(command.operationId()),result.record().orElseThrow().route().dueAt());}
                    return WarMapView.Receipt.rejected(result.reason());
                });
                return snapshot(p,replay?lease:service.refresh(lease,now()),request.page(),false,message(receipt));
            }
        }catch(IllegalArgumentException e){return snapshot(p,service.refresh(lease,now()),request.page(),false,message(WarMapView.Receipt.rejected(e.getMessage()==null?"UNAVAILABLE":e.getMessage())));}
        return snapshot(p,lease,request.page(),false,"当前操作不可用。");
    }
    public Update current(ServerPlayer p,UUID session,int page){var actor=CharacterRegistry.currentId(p);return actor==null?Update.closed(session,""):service.find(p.getUUID(),actor,session,now()).map(l->snapshot(p,l,page,false,"")).orElseGet(()->Update.closed(session,""));}
    public boolean leaveForManagement(ServerPlayer player, UUID session, long revision) {
        check();
        var actor = CharacterRegistry.currentId(player);
        if (actor == null || player.isSpectator() || !player.isAlive() || player.level().getServer() != server) return false;
        var lease = service.find(player.getUUID(), actor, session, now())
                .filter(l -> revision == l.revision() && sheet(player).filter(l.sheet()::equals).isPresent());
        lease.ifPresent(service::close);
        return lease.isPresent();
    }
    private Update snapshot(ServerPlayer p,WarMapService.Lease lease,int requestedPage,boolean open,String message){
        var core=WarServerEvents.existing(server);var actor=lease.character();
        var units=core.flatMap(WarServerRuntime::session).map(s->s.units().stream().filter(u->core.get().permission(actor,u.armyId())==WarCommandService.Permission.ALLOWED).sorted(Comparator.comparing(WarUnit::id)).toList()).orElse(List.of());
        int page=Math.clamp(requestedPage,0,Math.max(0,(units.size()-1)/WarMapView.PAGE_SIZE));var rows=new ArrayList<WarMapView.Unit>();var reportsByArmy=new HashMap<UUID,List<WarFieldReport>>();
        for(var u:units.stream().skip((long)page*WarMapView.PAGE_SIZE).limit(WarMapView.PAGE_SIZE).toList()){
            var field=core.get().field();
            var combatants=core.get().mobilization().map(m->m.activeUnit(u.id()).stream()
                    .filter(s->s.request().role()==WarMilitiaRequest.Role.COMBATANT)
                    .map(s->s.request().characterId()).collect(java.util.stream.Collectors.toUnmodifiableSet())).orElse(Set.of());
            var reports=reportsByArmy.computeIfAbsent(u.armyId(),a->field.map(f->f.reports(actor,a)).orElse(List.of())).stream()
                    .filter(r->r.unit().equals(u.id())&&combatants.contains(r.character())
                            &&r.position().dimension().equals(lease.sheet().dimension())&&now()>=r.observedAt()&&now()-r.observedAt()<=1200).toList();
            var observed=reports.stream().sorted(Comparator.comparingLong(WarFieldReport::observedAt).reversed().thenComparing(WarFieldReport::character)).findFirst().map(r->new WarMapView.Observation(r.position(),r.observedAt(),r.state()));
            int org=field.flatMap(f->f.cohesion(actor,u.armyId(),u.id())).map(WarCohesion::organization).orElse(-1);
            String name = WarMusterRuntime.existing(server).map(m -> m.book()).flatMap(book ->
                    book.unit(u.id()).map(info -> book.army(info.army()).map(a -> a.name() + " / ").orElse("") + info.name())).orElse("");
            rows.add(new WarMapView.Unit(u.id(),u.armyId(),u.troopType(),u.revision(),combatants.size(),reports.size(),org,u.pendingOrders().size(),observed,name));
        }
        var view=new WarMapView(lease.id(),lease.revision(),now(),lease.expiresAt(),lease.sheet(),page,units.size(),rows,service.preview(lease,now()),service.receipt(lease));
        return new Update(lease.id(),open,Optional.of(view),message);
    }
    public static Optional<WarMapSheet> sheet(ServerPlayer p){
        var stack=p.getMainHandItem();if(!stack.is(Items.FILLED_MAP))return Optional.empty();var id=stack.get(DataComponents.MAP_ID);var data=MapItem.getSavedData(stack,p.level());
        return id==null||data==null||!data.dimension.equals(p.level().dimension())?Optional.empty():Optional.of(new WarMapSheet(id.id(),WarKey.parse(data.dimension.identifier().toString()),data.centerX,data.centerZ,data.scale));
    }
    public static WarIntent.Position target(ServerPlayer p,WarMapSheet sheet,int px,int pz){
        if(sheet(p).filter(sheet::equals).isEmpty())throw new IllegalArgumentException("MAP_CHANGED");
        int x=sheet.x(px),z=sheet.z(pz);MapItemSavedData data=MapItem.getSavedData(p.getMainHandItem(),p.level());
        if((Byte.toUnsignedInt(data.colors[pz*128+px])>>2)==0)throw new IllegalArgumentException("MAP_UNEXPLORED");
        var chunk=p.level().getChunkSource().getChunkNow(x>>4,z>>4);if(chunk==null)throw new IllegalArgumentException("TARGET_UNLOADED");
        int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1;var pos=new BlockPos(x,y,z);
        if(!p.level().getWorldBorder().isWithinBounds(pos)||y<=p.level().getMinY()||y+1>=p.level().getMaxY()
                ||!p.level().getBlockState(pos.below()).isFaceSturdy(p.level(),pos.below(),Direction.UP)
                ||!p.level().getBlockState(pos).getCollisionShape(p.level(),pos).isEmpty()||!p.level().getBlockState(pos.above()).getCollisionShape(p.level(),pos.above()).isEmpty())throw new IllegalArgumentException("TARGET_BLOCKED");
        return new WarIntent.Position(sheet.dimension(),x,y,z);
    }
    public static WarKey kind(WarMapView.Mode mode){return switch(mode){case MOVE->InitialWarDefinitions.MOVE;case HOLD->InitialWarDefinitions.HOLD;case RETREAT->InitialWarDefinitions.RETREAT;case RALLY->InitialWarDefinitions.RALLY;case CHARGE->InitialWarDefinitions.CHARGE;};}
    private static String message(WarMapView.Receipt r){if(r.outcome()==WarMapView.Outcome.ACCEPTED)return "军令已受理，正在等待送达。";return switch(r.reason()){
        case "MAP_UNEXPLORED"->"这里尚未绘入地图，请先侦察。";case "TARGET_UNLOADED"->"目标区域暂未加载，无法确认落脚点。";case "TARGET_CHANGED","TARGET_BLOCKED"->"目标落脚点已经改变或受阻，请重新选择。";
        case "PREVIEW_EXPIRED"->"目标预览已过期，请重新点击地图。";case "FRESH_OWN_REPORT_REQUIRED"->"缺少新近的己方报告，暂不能传令。";case "ARMY_AUTHORITY_REQUIRED"->"当前人物无权指挥这支部队。";
        case "SPEAR_OR_MOUNTED_SWORD_ROSTER_REQUIRED_FOR_CHARGE"->"突击需要已整备的长矛兵团或骑乘剑士兵团。";case "CHARGE_APPROACH_REQUIRES_8_TO_48_BLOCKS"->"突击目标须距集结点8至48格，高差不超过4格。";
        default->r.outcome()==WarMapView.Outcome.REVIEW?"军令结果需要核对，请先查看部队状态。":"军令未受理，请刷新部队状态后重试。";};}
    private long now(){return server.overworld().getGameTime();}private void check(){if(!server.isSameThread())throw new IllegalStateException("map requires server thread");}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var r=RUNTIMES.get(p.level().getServer());if(r!=null)r.service.logout(p.getUUID());}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){RUNTIMES.remove(e.getServer());}
}
