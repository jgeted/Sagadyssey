package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.google.gson.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.gui.*;
import com.jgeted.sagadyssey.social.war.api.WarMapView;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.input.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Drives native Screen events and real client connections; never sends Windows keyboard/mouse input. */
@EventBusSubscriber(modid="sagadyssey",value=Dist.CLIENT)
public final class WarNetworkClientGameTests {
    private static int stage,ticks,entered,attempts;private static long changed=System.nanoTime();private static Connection observed;
    private static volatile boolean observerReady;private static final Queue<Update> updates=new ConcurrentLinkedQueue<>();
    private static final List<String> actions=new ArrayList<>(),messages=new ArrayList<>();private static final Queue<ClickEvent.RunCommand> confirmations=new ConcurrentLinkedQueue<>();
    private static final Queue<String> captures=new ConcurrentLinkedQueue<>();
    private static Request oldRequest;private static long sequence;private static int cash;private static UUID originalBody,originalContract;private static double originalDraw;private static boolean done;
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.multiplayer.client");}
    private static Path shared(){return Path.of(System.getProperty("sagadyssey.war.multiplayer.shared"));}
    private static boolean other(){return System.getProperty("sagadyssey.war.multiplayer.role").equals("other");}
    private static boolean mounted(){return Boolean.getBoolean("sagadyssey.war.multiplayer.mounted");}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent event){if(enabled()){messages.add(event.getMessage().getString());clicks(event.getMessage());}}
    private static void clicks(Component c){if(c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand click&&click.command().contains("saga war muster confirm"))confirmations.add(click);c.getSiblings().forEach(WarNetworkClientGameTests::clicks);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!enabled()||done)return;var mc=Minecraft.getInstance();ticks++;
        try{
            if(Files.isRegularFile(shared().resolve("network-abort.json"))){done=true;mc.stop();return;}
            if(stage==26&&flag(read(shared().resolve("network-final-proof.json")),"complete")){done=true;mc.stop();return;}
            if(System.nanoTime()-changed>120_000_000_000L)throw new IllegalStateException("stage timed out: "+stage+" screen="+(mc.screen==null?"none":mc.screen.getClass().getSimpleName())+" messages="+messages);
            if(ticks%4!=0)return;
            if(stage==0){if(mc.screen instanceof TitleScreen&&mc.getOverlay()==null){connect(mc);next(1,"native-connect");}return;}
            if(stage==18&&!other()&&mc.level==null&&mc.getOverlay()==null){connect(mc);next(19,"native-same-account-reconnect");return;}
            if(mc.player==null||mc.getConnection()==null||mc.level==null)return;
            observe(mc);if(!observerReady)return;
            var s=read(shared().resolve("network-state.json"));if(s.isEmpty())return;
            if(other()){other(mc,s);return;}
            owner(mc,s);
        }catch(Throwable failure){
            var result=new LinkedHashMap<String,Object>();result.put("pass",false);result.put("stage",stage);result.put("error",failure.toString());result.put("actions",actions);result.put("messages",messages);
            result.put("serverState",read(shared().resolve("network-state.json")));if(mc.player!=null){result.put("clientSelectedSlot",mc.player.getInventory().getSelectedSlot());result.put("clientHeldItem",mc.player.getMainHandItem().toString());}
            write(shared().resolve(other()?"other-client-result.json":"owner-client-result.json"),new Gson().toJsonTree(result).getAsJsonObject());failure.printStackTrace();done=true;mc.stop();
        }
    }
    private static void owner(Minecraft mc,JsonObject s)throws Exception{
        var n=mc.level.getEntity(s.get("entity").getAsInt());
        switch(stage){
            case 1 -> {if(n instanceof NpcBase){originalBody=n.getUUID();select(mc,0);mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,n.getEyePosition());mc.gameMode.interact(mc.player,n,InteractionHand.MAIN_HAND);next(2,"original-npc-interaction");}}
            case 2 -> {if(mc.screen instanceof NpcContextScreen){click(mc,"提出服务约定");next(3,"native-civil-recruit-button");}}
            case 3 -> {if(flag(s,"recruited")){select(mc,1);shift(mc,true);mc.gameMode.interact(mc.player,n,InteractionHand.MAIN_HAND);next(4,"native-sword-handover-prompt");}}
            case 4 -> {if(mc.screen instanceof NpcHandoverIntentScreen){click(mc,"赠礼");shift(mc,false);next(5,"native-sword-gift-button");}}
            case 5 -> {if(number(s,"sword")>=1){select(mc,2);shift(mc,true);mc.gameMode.interact(mc.player,n,InteractionHand.MAIN_HAND);next(6,"native-food-handover-prompt");}}
            case 6 -> {if(mc.screen instanceof NpcHandoverIntentScreen){click(mc,"数量：1 个");click(mc,"赠礼");shift(mc,false);next(7,"native-full-stack-food-gift");}}
            case 7 -> {if(number(s,"bread")>=32){select(mc,3);command(mc,"saga war muster create NetworkArmy");next(8,"native-create-army-command");}}
            case 8 -> {if(flag(s,"armyOwned")){command(mc,"saga war muster unit NetworkArmy Workers "+(mounted()?"light_cavalry":"sword"));next(9,"native-create-unit-command");}}
            case 9 -> {if(flag(s,"unitReady")){mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,n.getEyePosition());next(10,"native-raycast-alignment");}}
            case 10 -> {confirmations.clear();command(mc,"saga war muster offer NetworkArmy Workers 64 1");attempts++;next(11,"native-service-proposal");}
            case 11 -> {var c=confirmations.poll();if(c!=null){ClickInvoker.run(mc,c);next(12,"native-received-chat-confirmation");}}
            case 12 -> {
                if(text(s,"offerStage").equals("DECLINED")){if(attempts>=4)throw new IllegalStateException("four independently declined offers");next(9,"retain-declined-decision-new-offer");}
                else if(text(s,"offerStage").equals("ACTIVE")){originalContract=UUID.fromString(text(s,"contract"));originalDraw=s.get("draw").getAsDouble();command(mc,"saga war map");next(13,"native-open-map-command");}
            }
            case 13 -> {if(mc.screen instanceof WarMapScreen12111 screen){var view=view(screen);if(!view.units().isEmpty()){capture(mc,"network-map-roster.png");click(mc,"坚守");next(14,"native-map-hold-button");}}}
            case 14 -> {if(mc.screen instanceof WarMapScreen12111 screen){var v=view(screen);int x=(int)Math.round(v.sheet().pixelX(6)),z=(int)Math.round(v.sheet().pixelZ(6));mapClick(screen,x,z);next(15,"native-map-surface-preview");}}
            case 15 -> {if(mc.screen instanceof WarMapScreen12111 screen){var v=view(screen);if(v.preview().isPresent()){
                oldRequest=request(v);var preview=new Gson().toJsonTree(oldRequest).getAsJsonObject();preview.addProperty("sourceMapId",v.sheet().id());write(shared().resolve("owner-preview.json"),preview);sequence=number(s,"orderSequence");capture(mc,"network-map-preview.png");next(16,"actual-server-preview-token");
            }}}
            case 16 -> {var result=read(shared().resolve("other-client-result.json"));if(flag(result,"pass")){
                require(number(s,"orderSequence")==sequence,"foreign connection changed original order");require(text(result,"serverPermission").equals("DENIED"),"OP adversary must not command another army");click(mc,"确认发令");next(17,"native-map-confirm-button");
            }}
            case 17 -> {if(flag(s,"holding")&&flag(s,"heldSword")){require(number(s,"orderSequence")==sequence+1,"one preview produces exactly one order");if(mc.screen!=null)mc.screen.onClose();mc.getConnection().getConnection().disconnect(Component.literal("background reconnect verification"));mc.disconnectWithProgressScreen();mc.setScreen(new TitleScreen());next(18,"actual-tcp-disconnect");}}
            case 18 -> { } // disconnected handling is performed before requiring a live player
            case 19 -> {if(number(readPlayer(s,"WarLord"),"joins")>=2){require(n.getUUID().equals(originalBody)&&text(s,"contract").equals(originalContract.toString())&&s.get("draw").getAsDouble()==originalDraw,"rejoin changed original service");updates.clear();ClientPacketDistributor.sendToServer(new RequestPayload(oldRequest));next(20,"stale-session-over-new-tcp-connection");}}
            case 20 -> {if(closedUpdate()){
                require(number(s,"orderSequence")==sequence+1,"stale session issued an order");command(mc,"saga war select "+text(s,"unit"));next(201,"native-reselect-after-logout-cleared-selection");
            }}
            case 201 -> {if(ticks-entered>=8){select(mc,3);var target=new Vec3(5.5,80.6,9.5);mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,target);var hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));require(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(new BlockPos(5,80,9)),"actual banner raycast");mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);next(21,"native-map-banner-use-packet");}}
            case 21 -> {if(flag(s,"postBound")){select(mc,4);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);next(22,"native-goat-horn-use-packet");}}
            case 22 -> {if(text(s,"receivedKind").endsWith("/rally")){mc.gameMode.releaseUsingItem(mc.player);select(mc,3);next(219,"actual-local-horn-rally-received");}}
            case 219 -> {if(flag(readPlayer(s,"WarLord"),"heldMap")){command(mc,"saga war map");next(220,"server-acknowledged-held-map-before-map-command");}}
            case 220 -> {if(mc.screen instanceof WarMapScreen12111){click(mc,mounted()?"突击":"坚守");next(221,mounted()?"native-sword-cavalry-charge-button":"native-return-from-rally-to-hold");}}
            case 221 -> {if(mc.screen instanceof WarMapScreen12111 screen){var v=view(screen);mapClick(screen,(int)Math.round(v.sheet().pixelX(mounted()?18:6)),(int)Math.round(v.sheet().pixelZ(mounted()?9:6)));next(222,"native-second-map-preview");}}
            case 222 -> {if(mc.screen instanceof WarMapScreen12111 screen&&view(screen).preview().isPresent()){click(mc,"确认发令");next(223,"native-second-map-confirmation");}}
            case 223 -> {if(mounted()?text(s,"receivedKind").endsWith("/charge"):flag(s,"holding")&&text(s,"receivedKind").endsWith("/hold")){if(mc.screen!=null)mc.screen.onClose();select(mc,0);mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,n.getEyePosition());write(shared().resolve("network-control.json"),object("attack",true));next(23,mounted()?"native-cavalry-charge-received":"native-hold-ready-for-actual-self-defense");}}
            case 23 -> {mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,n.getEyePosition());if(flag(s,"actualSwordDamage")){capture(mc,"network-native-combat.png");command(mc,"saga war muster retire "+originalContract);next(24,"native-retirement-command");}}
            case 24 -> {if(flag(s,"ended")&&flag(s,"closingPaid")){require(!flag(s,"workBlocked"),"original labor still held");require(number(s,"payerEmeralds")+number(s,"npcEmeralds")==64,"actual original cash not conserved");cash=(int)number(s,"payerEmeralds");command(mc,"saga war muster retire "+originalContract);next(25,"native-idempotent-retirement-command");}}
            case 25 -> {if(ticks-entered>=80){require(number(s,"payerEmeralds")==cash&&number(s,"payerEmeralds")+number(s,"npcEmeralds")==64,"replayed retirement payment");require(n.getUUID().equals(originalBody)&&text(s,"profession").equals("WORKER")&&text(s,"command").equals(mounted()?"FOLLOW":"STAY"),"original civilian context changed");
                require(s.getAsJsonArray("nativeSwordContacts").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(c->flag(c,"sword")&&(!mounted()||flag(c,"mountedOnOriginalHorse"))),"original native sword contact and mounted identity required");
                var result=object("pass",true);result.addProperty("process",ProcessHandle.current().pid());result.addProperty("realNativeClient",true);result.addProperty("hiddenThroughout",true);result.addProperty("sameOriginalServiceAfterTcpReconnect",true);result.addProperty("body",originalBody.toString());result.addProperty("contract",originalContract.toString());result.add("actions",new Gson().toJsonTree(actions));result.add("messages",new Gson().toJsonTree(messages));result.add("serverFinal",s);write(shared().resolve("owner-client-result.json"),result);write(shared().resolve("network-control.json"),object("complete",true));next(26,"await-dedicated-final-ack-before-disconnect");
            }}
            case 26 -> { }
            default -> throw new IllegalStateException("unknown owner stage "+stage);
        }
    }
    private static void other(Minecraft mc,JsonObject s){
        if(stage==1){var preview=read(shared().resolve("owner-preview.json"));if(!preview.isEmpty()&&text(s,"otherPermission").equals("DENIED")){
            oldRequest=new Gson().fromJson(preview,Request.class);sequence=number(s,"orderSequence");select(mc,3);next(11,"native-same-map-copy-in-adversary-hand");
        }}else if(stage==11){var preview=read(shared().resolve("owner-preview.json"));var player=readPlayer(s,"WarOther");if(flag(player,"heldMap")&&number(player,"mapId")==number(preview,"sourceMapId")){
            require(flag(player,"operator"),"adversary must actually have OP permissions");updates.clear();ClientPacketDistributor.sendToServer(new RequestPayload(oldRequest));next(2,"foreign-owner-session-over-real-op-connection");
        }}else if(stage==2&&closedUpdate()){
            require(number(s,"orderSequence")==sequence,"unauthorized order changed server");var result=object("pass",true);result.addProperty("serverPermission",text(s,"otherPermission"));result.addProperty("realNativeClient",true);result.addProperty("nativeServerDeniedForeignSession",true);result.addProperty("orderSequenceUnchanged",true);result.add("actions",new Gson().toJsonTree(actions));result.add("serverPlayers",s.get("players"));write(shared().resolve("other-client-result.json"),result);done=true;mc.stop();
        }
    }
    private static void observe(Minecraft mc){
        var c=mc.getConnection().getConnection();if(c==observed)return;observed=c;observerReady=false;updates.clear();
        c.channel().eventLoop().execute(()->{c.channel().pipeline().addBefore("packet_handler","war_native_client_observer",new ChannelInboundHandlerAdapter(){
            @Override public void channelRead(ChannelHandlerContext context,Object packet)throws Exception{if(packet instanceof ClientboundCustomPayloadPacket p&&p.payload() instanceof UpdatePayload u)updates.add(u.value());context.fireChannelRead(packet);}
        });observerReady=true;});
    }
    private static void connect(Minecraft mc){var address=System.getProperty("sagadyssey.war.multiplayer.address");ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("Sagadyssey isolated verification",address,ServerData.Type.OTHER),false,null);}
    private static void select(Minecraft mc,int slot){mc.player.getInventory().setSelectedSlot(slot);mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));}
    private static void shift(Minecraft mc,boolean down){mc.options.keyShift.setDown(down);var input=mc.player.input.keyPresses;mc.player.input.keyPresses=new net.minecraft.world.entity.player.Input(input.forward(),input.backward(),input.left(),input.right(),input.jump(),down,input.sprint());mc.getConnection().send(new ServerboundPlayerInputPacket(mc.player.input.keyPresses));}
    private static void command(Minecraft mc,String command){mc.getConnection().sendCommand(command);}
    private static void click(Minecraft mc,String text){
        if(mc.screen==null)throw new IllegalStateException("screen absent for "+text);
        var button=mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.active&&b.visible&&b.getMessage().getString().contains(text)).findFirst().orElseThrow(()->new IllegalStateException("button absent: "+text+" screen="+mc.screen.getClass().getSimpleName()));
        var event=new MouseButtonEvent(button.getX()+button.getWidth()/2D,button.getY()+button.getHeight()/2D,new MouseButtonInfo(0,0));var screen=mc.screen;require(screen.mouseClicked(event,false),"actual button event was not consumed: "+text);screen.mouseReleased(event);
    }
    private static void mapClick(WarMapScreen12111 screen,int px,int pz)throws Exception{var event=new MouseButtonEvent(integer(screen,"mapX")+(px+.5)*integer(screen,"mapSize")/128,integer(screen,"mapY")+(pz+.5)*integer(screen,"mapSize")/128,new MouseButtonInfo(0,0));require(screen.mouseClicked(event,false),"actual map surface click");}
    private static int integer(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.getInt(object);}
    private static WarMapView view(WarMapScreen12111 screen)throws Exception{var field=WarMapScreen12111.class.getDeclaredField("view");field.setAccessible(true);return (WarMapView)field.get(screen);}
    private static Request request(WarMapView v){var p=v.preview().orElseThrow();return new Request(v.session(),v.revision(),Action.ISSUE,v.page(),p.unit(),p.mode(),0,0,p.ticket());}
    private static boolean closedUpdate(){Update u;while((u=updates.poll())!=null)if(u.view().isEmpty()&&!u.openScreen())return true;return false;}
    private static void capture(Minecraft mc,String name){mc.getToastManager().clear();captures.add(name);}
    @SubscribeEvent public static void rendered(RenderFrameEvent.Post event){if(enabled()&&!captures.isEmpty()){var mc=Minecraft.getInstance();String name=captures.poll();net.minecraft.client.Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,c->System.out.println("WAR_NATIVE_CAPTURE "+name));}}
    private static void next(int value,String action){stage=value;entered=ticks;changed=System.nanoTime();actions.add(action);System.out.println("WAR_NETWORK_CLIENT "+(other()?"other":"lord")+" stage="+value+" "+action);}
    private static JsonObject readPlayer(JsonObject s,String name){return s.has("players")&&s.getAsJsonObject("players").has(name)?s.getAsJsonObject("players").getAsJsonObject(name):new JsonObject();}
    private static boolean flag(JsonObject p,String key){return p.has(key)&&!p.get(key).isJsonNull()&&p.get(key).getAsBoolean();}
    private static long number(JsonObject p,String key){return p.has(key)?p.get(key).getAsLong():0;}
    private static String text(JsonObject p,String key){return p.has(key)?p.get(key).getAsString():"";}
    private static JsonObject object(String key,boolean value){var p=new JsonObject();p.addProperty(key,value);return p;}
    private static JsonObject read(Path p){try{return Files.isRegularFile(p)?JsonParser.parseString(Files.readString(p)).getAsJsonObject():new JsonObject();}catch(Exception ignored){return new JsonObject();}}
    private static void write(Path p,JsonObject value){try{var temporary=p.resolveSibling(p.getFileName()+".writing");Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(value));Files.move(temporary,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static final class ClickInvoker extends Screen {private ClickInvoker(){super(Component.empty());}static void run(Minecraft mc,ClickEvent click){defaultHandleGameClickEvent(click,mc,mc.screen);}}
}
