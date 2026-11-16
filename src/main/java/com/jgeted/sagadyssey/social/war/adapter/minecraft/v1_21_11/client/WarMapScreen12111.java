package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.*;
import java.util.*;
import java.util.function.Consumer;
import static com.jgeted.sagadyssey.social.war.api.WarMapInteraction.NONE;

/** Vanilla map terrain with bounded own-report overlays. A map click previews; only the confirm button submits. */
public final class WarMapScreen12111 extends Screen {
    private WarMapView view; private String message; private UUID selected; private WarMapView.Mode mode=WarMapView.Mode.MOVE;
    private final Consumer<Request> sender; private final MapItemSavedData renderMap; private final MapRenderState mapState=new MapRenderState();
    private final Consumer<WarMapView> management;
    private int left,top,panelW,panelH,listW,mapX,mapY,mapSize,rowHeight,ticks,receivedAt,waitingSince;private boolean waiting,small,closed;private Button confirm;
    /** Alternative transport and map surface also permit isolated native client rendering checks. */
    public WarMapScreen12111(Update update,Consumer<Request> sender,MapItemSavedData renderMap){
        this(update,sender,renderMap,null);
    }
    public WarMapScreen12111(Update update,Consumer<Request> sender,MapItemSavedData renderMap,Consumer<WarMapView> management){
        super(Component.literal("军令地图"));this.view=update.view().orElseThrow();this.message=update.message();this.sender=Objects.requireNonNull(sender);this.renderMap=renderMap;
        this.management=management;
        selected=view.units().isEmpty()?null:view.units().getFirst().id();
    }
    public UUID session(){return view.session();}
    public void accept(Update update){
        if(!session().equals(update.session()))return;
        if(update.view().isEmpty()){closed=true;if(minecraft.player!=null&&!update.message().isBlank())minecraft.player.displayClientMessage(Component.literal(update.message()),true);minecraft.setScreen(null);return;}
        if(update.view().get().revision()<view.revision())return;
        view=update.view().get();message=update.message();waiting=false;receivedAt=ticks;
        if(view.units().stream().noneMatch(u->u.id().equals(selected)))selected=view.units().isEmpty()?null:view.units().getFirst().id();
        rebuildWidgets();
    }
    @Override protected void init(){
        panelW=Math.min(680,width-16);panelH=Math.min(400,height-16);left=(width-panelW)/2;top=(height-panelH)/2;small=panelW<344||panelH<214;
        if(small){button("关闭",left+10,top+45,70,18,this::onClose);if(management!=null)button("军籍簿",left+90,top+45,72,18,this::openManagement);return;}
        listW=Math.min(200,Math.max(138,panelW/3));mapSize=Math.min(256,Math.min(panelH-112,panelW-listW-42));mapX=left+listW+26;mapY=top+48;
        rowHeight=Math.min(26,(panelH-134)/WarMapView.PAGE_SIZE);
        button("刷新",left+panelW-104,top+9,46,18,()->request(Action.REFRESH,view.page(),0,0));button("关闭",left+panelW-54,top+9,44,18,this::onClose);
        if(management!=null)button("军籍簿",left+94,top+9,54,18,this::openManagement);
        var field=button("定为现场",left+154,top+9,64,18,()->request(Action.SELECT_FIELD,view.page(),0,0));field.active=selected!=null&&!waiting;
        for(int i=0;i<view.units().size();i++){
            var u=view.units().get(i);var b=button((u.id().equals(selected)?"› ":"")+(view.page()*WarMapView.PAGE_SIZE+i+1)+" "+(u.name().isBlank()?loadout(u.loadout()):u.name()),left+10,top+48+i*rowHeight,listW,rowHeight>=24?16:rowHeight-2,()->{selected=u.id();message="点击地图选择目标，确认后才会发令。";rebuildWidgets();});
            b.active=!waiting;
        }
        var previous=button("上一页",left+10,top+panelH-83,57,18,()->request(Action.REFRESH,view.page()-1,0,0));previous.active=view.page()>0&&!waiting;
        var next=button("下一页",left+listW-47,top+panelH-83,57,18,()->request(Action.REFRESH,view.page()+1,0,0));next.active=(view.page()+1)*WarMapView.PAGE_SIZE<view.totalUnits()&&!waiting;
        int modeW=(panelW-28)/5;
        for(var m:WarMapView.Mode.values())button((m==mode?"● ":"")+label(m),left+10+m.ordinal()*modeW,top+panelH-54,modeW-4,18,()->{mode=m;message="点击地图预览“"+label(m)+"”目标。";rebuildWidgets();});
        confirm=button("确认发令",left+panelW-104,top+panelH-29,94,19,()->request(Action.ISSUE,view.page(),0,0));confirm.active=validPreview()&&!waiting;
    }
    private void openManagement(){if(!waiting){waiting=true;waitingSince=ticks;management.accept(view);}}
    private Button button(String text,int x,int y,int w,int h,Runnable action){var b=Button.builder(Component.literal(text),v->action.run()).bounds(x,y,w,h).build();addRenderableWidget(b);return b;}
    private long now(){return view.observedAt()+Math.max(0,ticks-receivedAt);}
    private boolean validPreview(){return view.preview().filter(p->p.unit().equals(selected)&&p.mode()==mode&&now()<=p.expiresAt()).isPresent();}
    private void request(Action action,int page,int px,int pz){
        if(waiting||page<0)return;UUID ticket=action==Action.ISSUE?view.preview().map(WarMapView.Preview::ticket).orElse(NONE):NONE;
        waiting=true;waitingSince=ticks;message="正在等待服务器回执…";
        sender.accept(new Request(session(),view.revision(),action,page,selected==null?NONE:selected,mode,px,pz,ticket));
        rebuildWidgets();
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(!small&&event.button()==0&&!waiting&&selected!=null&&event.x()>=mapX&&event.x()<mapX+mapSize&&event.y()>=mapY&&event.y()<mapY+mapSize){
            int x=(int)((event.x()-mapX)*128/mapSize),z=(int)((event.y()-mapY)*128/mapSize);request(Action.PREVIEW,view.page(),x,z);return true;
        }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public void tick(){
        ticks++;if(confirm!=null)confirm.active=validPreview()&&!waiting;
        if(waiting&&ticks-waitingSince>=60){waiting=false;message="回执尚未到达，请刷新核对军令状态。";rebuildWidgets();}
        if(!waiting&&(view.preview().isEmpty()||view.preview().get().expiresAt()<now())&&ticks-receivedAt>=40&&now()<=view.expiresAt())request(Action.REFRESH,view.page(),0,0);
        if(now()>view.expiresAt()){message="会话已过期，请关闭后重新打开地图。";if(confirm!=null)confirm.active=false;}
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        g.fill(0,0,width,height,0xB0111518);g.fill(left,top,left+panelW,top+panelH,0xF51B242B);g.fill(left,top,left+panelW,top+2,0xFFCCAC68);
        text(g,"军令地图",left+12,top+13,0xFFE6D6AF,Math.max(100,panelW-130));
        if(small){text(g,"界面空间不足，请降低 GUI 缩放。",left+10,top+30,0xFFE2D1AD,panelW-20);super.render(g,mouseX,mouseY,partialTick);return;}
        var s=view.sheet();text(g,dimension(s.dimension())+" · 每格 "+(1<<s.scale())+" 方块 · 己方报告",left+12,top+32,0xFFA9B9BD,panelW-24);
        if(view.units().isEmpty()){text(g,"暂无可指挥的已编组部队",left+12,top+56,0xFFB8BCC1,listW-4);text(g,"打开军籍簿建立队伍",left+12,top+72,0xFF87999F,listW-4);}
        if(rowHeight>=24)for(int i=0;i<view.units().size();i++){
            var u=view.units().get(i);String line=u.members()+"人 · 报告"+u.reports()+(u.organization()<0?"":" · 组织"+u.organization()/100+"%")+(u.pending()==0?"":" · 待送"+u.pending());
            text(g,line,left+13,top+48+i*rowHeight+17,0xFF9AAFB0,listW-6);
        }
        int detailY=top+48+view.units().size()*rowHeight+8;
        if(detailY+48<top+panelH-90)view.units().stream().filter(u->u.id().equals(selected)).findFirst().ifPresent(u->{
            text(g,"所选部队 · "+u.members()+" 人",left+12,detailY,0xFFD9C590,listW-4);
            if(u.observation().isPresent()){var o=u.observation().get();text(g,state(o.state())+" · 约 "+Math.max(0,(now()-o.observedAt())/20)+" 秒前",left+12,detailY+14,0xFFB4C4BD,listW-4);text(g,"报告点 "+o.position().x()+", "+o.position().z(),left+12,detailY+28,0xFF8EA6A6,listW-4);}
            else text(g,"尚无这张地图上的新近报告",left+12,detailY+14,0xFF8EA6A6,listW-4);
        });
        text(g,(view.page()+1)+" / "+Math.max(1,(view.totalUnits()+5)/6),left+75,top+panelH-78,0xFFC6C8BD,48);
        g.fill(mapX-2,mapY-2,mapX+mapSize+2,mapY+mapSize+2,0xFFB59B68);g.fill(mapX,mapY,mapX+mapSize,mapY+mapSize,0xFFB7AC89);
        var data=renderMap!=null?renderMap:minecraft.level==null?null:MapItem.getSavedData(new MapId(s.id()),minecraft.level);
        if(data!=null){minecraft.getMapRenderer().extractRenderState(new MapId(s.id()),data,mapState);g.pose().pushMatrix();g.pose().translate((float)mapX,(float)mapY);g.pose().scale(mapSize/128F);g.submitMapRenderState(mapState);g.pose().popMatrix();}
        else text(g,"地图数据同步中…",mapX+8,mapY+mapSize/2,0xFF403B2A,mapSize-16);
        g.enableScissor(mapX,mapY,mapX+mapSize,mapY+mapSize);
        for(int i=0;i<view.units().size();i++){
            var u=view.units().get(i);if(u.observation().isEmpty())continue;var o=u.observation().get();double px=s.pixelX(o.position().x()),pz=s.pixelZ(o.position().z());if(px<0||px>=128||pz<0||pz>=128)continue;
            int x=mapX+(int)(px*mapSize/128),z=mapY+(int)(pz*mapSize/128);boolean chosen=u.id().equals(selected);int color=now()-o.observedAt()>40?0xFFA4A8AA:chosen?0xFFFFD37B:0xFF8CCABB;
            g.fill(x-3,z-3,x+4,z+4,0xFF1D262A);g.fill(x-2,z-2,x+3,z+3,color);g.drawString(font,""+(view.page()*6+i+1),x+5,z-3,color,false);
            if(Math.abs(mouseX-x)<=8&&Math.abs(mouseY-z)<=8)message=state(o.state())+" · 约 "+Math.max(0,(now()-o.observedAt())/20)+" 秒前的报告";
        }
        if(validPreview()){
            var p=view.preview().orElseThrow();int x=mapX+(int)(s.pixelX(p.target().x())*mapSize/128),z=mapY+(int)(s.pixelZ(p.target().z())*mapSize/128);g.fill(x-6,z,x+7,z+1,0xFFE9AF63);g.fill(x,z-6,x+1,z+7,0xFFE9AF63);
        }
        g.disableScissor();text(g,"北 ↑",mapX+mapSize-29,mapY+5,0xFF222B28,28);
        int helpX=mapX+mapSize+16,helpW=left+panelW-helpX-12;
        if(helpW>=106){
            text(g,validPreview()?"待确认军令":"部署步骤",helpX,mapY+8,0xFFD9C590,helpW);
            String[] lines=validPreview()?new String[]{label(view.preview().orElseThrow().mode()),"目标已标在地图上","检查坐标和传令时间","点击“确认发令”送出","","军令受理后仍需送达","到位情况以部队报告为准"}:new String[]{"1 选择部队","2 选择军令","3 点击地图预览","4 确认发令","","标记来自己方报告","灰色标记已过时","未绘制区域需先侦察"};
            for(int i=0;i<lines.length;i++)text(g,lines[i],helpX,mapY+29+i*15,0xFFA0B4B1,helpW);
        }
        if(mapSize+32<panelH-100){int y=mapY+mapSize+9;text(g,"亮色：新报告   灰色：旧报告",mapX,y,0xFF9FAFA9,panelW-listW-40);}
        String footer=message;
        if(validPreview()){var p=view.preview().orElseThrow();footer=p.target().x()+", "+p.target().y()+", "+p.target().z()+" · "+(p.delivery()==WarMapView.Delivery.DIRECT?"现场":"传令")+"约 "+p.delay()+" 刻 · 确认剩余 "+Math.max(0,(p.expiresAt()-now()+19)/20)+" 秒";}
        else if(view.receipt().filter(r->r.outcome()==WarMapView.Outcome.ACCEPTED).isPresent())footer=message.isBlank()?"军令已受理；送达以部队报告为准。":message;
        text(g,footer,left+12,top+panelH-24,waiting?0xFFE5C782:0xFFBCCBC7,panelW-126);
        super.render(g,mouseX,mouseY,partialTick);
    }
    private void text(GuiGraphics g,String text,int x,int y,int color,int maxWidth){if(maxWidth>0)g.drawString(font,font.plainSubstrByWidth(text,maxWidth),x,y,color,false);}
    private static String loadout(WarKey key){return InitialWarDefinitions.LOADOUTS.find(key).map(l->l.mount().label()+" · "+l.primaryWeapon().label()).orElse("部队");}
    public static String label(WarMapView.Mode mode){return mode.label();}
    private static String dimension(WarKey key){return switch(key.toString()){case "minecraft:overworld"->"主世界";case "minecraft:the_nether"->"下界";case "minecraft:the_end"->"末地";default->key.toString();};}
    private static String state(WarFieldReport.State s){return switch(s){case WAITING->"待命";case MOVING->"行军中";case HOLDING->"坚守中";case ENGAGING->"交战中";case RETREATING->"撤退中";case REGROUPING->"集结中";case BLOCKED->"受阻";case REVIEW->"待核对";};}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partialTick){}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!closed){closed=true;sender.accept(new Request(session(),view.revision(),Action.CLOSE,view.page(),NONE,mode,0,0,NONE));}super.onClose();}
}
