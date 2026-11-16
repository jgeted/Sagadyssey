package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.google.gson.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in native rendering/input fixture only. No world, fake mobilization, or production JAR entry. */
@EventBusSubscriber(modid="sagadyssey",value=Dist.CLIENT)
public final class WarMapScreenGameTests {
    private static boolean opened;private static int frames;private static WarMapView view;private static List<WarMapView.Unit> roster;private static final UUID SESSION=new UUID(20261112,42);private static final List<String> inputs=new ArrayList<>();
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();String configured=System.getProperty("sagadyssey.war.map.clientRoot","");
        if(configured.isEmpty()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(Path.of(configured).toAbsolutePath().normalize()))return;
        if(opened){
            if(Boolean.getBoolean("sagadyssey.war.map.capture")&&mc.getOverlay()==null&&mc.screen instanceof WarMapScreen12111){
                frames++;if(frames==20)net.minecraft.client.Screenshot.grab(mc.gameDirectory,"war-map-native.png",mc.getMainRenderTarget(),1,c->System.out.println("WAR_MAP_RENDER_EXPORT "+c.getString()));
                if(frames>=80&&Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/war-map-native.png")))mc.stop();
            }
            return;
        }
        if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
        opened=true;mc.getWindow().setTitle("Sagadyssey - War Map Verification");
        var data=MapItemSavedData.createForClient((byte)0,false,Level.OVERWORLD);
        for(int z=0;z<128;z++)for(int x=0;x<128;x++)data.colors[z*128+x]=(byte)(Math.abs(x-64-(int)(Math.sin(z*.09)*14))<5?49:(z/12+x/14)%5==0?42:29);
        var sheet=new WarMapSheet(20261112,WarKey.parse("minecraft:overworld"),0,0,0);var rows=new ArrayList<WarMapView.Unit>();
        for(int i=0;i<6;i++){var mount=i<2?WarLoadout.Mount.FOOT:i<4?WarLoadout.Mount.LIGHT:WarLoadout.Mount.HEAVY;var weapon=i%2==0?WarLoadout.Weapon.SWORD:WarLoadout.Weapon.BOW;int count=12+i*4;rows.add(new WarMapView.Unit(new UUID(0,i+1),new UUID(7,8),new WarLoadout(mount,weapon,false,false).id(),1,count,count,8500-i*500,i==2?1:0,Optional.of(new WarMapView.Observation(new WarIntent.Position(sheet.dimension(),-40+i*15,65,-30+i*11),i<4?990:850,WarFieldReport.State.HOLDING))));}
        roster=List.copyOf(rows);String scene=System.getProperty("sagadyssey.war.map.layout","roster");var preview=scene.equals("preview")?Optional.of(new WarMapView.Preview(new UUID(1,42),rows.getFirst().id(),WarMapView.Mode.MOVE,new WarIntent.Position(sheet.dimension(),28,65,-18),1120,WarMapView.Delivery.COURIER,80)):Optional.<WarMapView.Preview>empty();
        view=new WarMapView(SESSION,1,1000,1600,sheet,0,scene.equals("empty")?0:7,scene.equals("empty")?List.of():rows,preview,Optional.empty());
        mc.setScreen(new WarMapScreen12111(new Update(SESSION,true,Optional.of(view),"点击地图预览目标；数据为独立界面验收夹具。"),WarMapScreenGameTests::input,data));
    }
    private static void input(Request r){
        inputs.add(r.action()+":"+r.mode()+":"+r.pixelX()+","+r.pixelZ());
        if(r.action()!=Action.CLOSE){
            long now=view.observedAt()+2;Optional<WarMapView.Preview> preview=Optional.empty();var receipt=view.receipt();String message="界面验收夹具，未连接战场。";
            if(r.action()==Action.PREVIEW){preview=Optional.of(new WarMapView.Preview(UUID.randomUUID(),r.unit(),r.mode(),new WarIntent.Position(view.sheet().dimension(),view.sheet().x(r.pixelX()),65,view.sheet().z(r.pixelZ())),now+120,WarMapView.Delivery.COURIER,80));message="请确认目标。";}
            if(r.action()==Action.ISSUE){receipt=Optional.of(new WarMapView.Receipt(WarMapView.Outcome.ACCEPTED,"RENDER_FIXTURE_ONLY",Optional.of(UUID.randomUUID()),now+80));message="界面确认流程完成（夹具）。";}
            boolean empty=System.getProperty("sagadyssey.war.map.layout","").equals("empty");var rows=empty?List.<WarMapView.Unit>of():r.page()==0?initialRows():List.of(initialRows().getFirst());view=new WarMapView(SESSION,view.revision()+1,now,now+600,view.sheet(),r.page(),empty?0:7,rows,preview,receipt);
            if(Minecraft.getInstance().screen instanceof WarMapScreen12111 screen)screen.accept(new Update(SESSION,false,Optional.of(view),message));
        }
        try{Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("war-map-client-inputs.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("fixtureOnly",true,"inputs",inputs,"screenClass",WarMapScreen12111.class.getName())));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static List<WarMapView.Unit> initialRows(){
        return roster;
    }
}
