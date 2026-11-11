package com.jgeted.sagadyssey.social.religion.life;

import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.religion.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import java.util.*;

/** Explicit OP fixture only. Never run at world load; never overwrites blocks, items, or old NPCs. */
public final class ChurchLifeClientFixture {
    public static final List<String> JOBS=List.of("rite1","lesson","copy","visit","marriage","watch","maintenance","funeral","rite2","rite3");
    public static UUID id(UUID temple,String key){return ContainerIds.named("phase8-client:"+temple+":"+key);}
    public static String prepare(ServerPlayer p,TempleRecord t){var s=p.level().getServer();var d=ChurchLifeData.get(s);UUID person=ReligionService.character(p),marker=id(t.id(),"fixture-intent");
        var old=d.findRecord(marker);if(old!=null){if(!old.parties().contains(person))throw new IllegalStateException("本地夹具已绑定原测试人物，不覆盖原验收");return "本地测试夹具已有创建意图；不重复生成 NPC 或材料。用 /saga church demo-status "+t.id()+" 核对。";}
        if(!ReligionService.near(p,t))throw new IllegalStateException("请先到原教堂接待处");var box=SupplyFlowService.container(s,t.treasury());if(box==null||!SupplyFlowService.validOwner(s,t.treasury()))throw new IllegalStateException("原教堂财库须有效且保持原产权");
        var rights=RightsStateData.get(s);var slots=new ArrayList<Integer>();for(int i=0;i<box.getContainerSize();i++){int slot=i;if(box.getItem(i).isEmpty()&&rights.claims().stream().noneMatch(c->c.containerId().equals(t.treasury().containerId())&&c.slot()==slot))slots.add(i);}
        if(slots.size()<4)throw new IllegalStateException("请在教堂普通财库留出四个真正空槽；不移动或覆盖已有物品");
        var places=new ArrayList<BlockPos>();for(int radius:new int[]{2,3,6,12,20})for(int[] dir:new int[][]{{-1,0},{0,-1},{0,1},{1,0},{-1,-1},{-1,1},{1,-1},{1,1}}){var pos=t.counter().offset(radius*dir[0],0,radius*dir[1]);
            if(!ChurchLifeService.usable(s,t,pos)||p.level().getEntitiesOfClass(NpcBase.class,new AABB(pos).inflate(.7)).size()>0)continue;
            var hit=p.level().clip(new ClipContext(p.getEyePosition(),Vec3.atCenterOf(pos),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));if(hit.getType()!=HitResult.Type.MISS)continue;places.add(pos);}
        if(places.size()<6)throw new IllegalStateException("附近需六处有支撑、无遮挡的空闲站位；不拆除或覆盖你的建筑");
        if(d.activities().size()+JOBS.size()>4096||d.records().size()+16>4096)throw new IllegalStateException("领域账本容量不足，须先明确归档");long now=p.level().getGameTime();
        // Intent is durable before explicit debug material creation; uncertain restart never replays creation.
        d.put(new ChurchRecord(marker,t.id(),null,null,person,t.treasury().containerId(),now,"测试夹具创建意图","测试者明确创建新测试人物、教材及 12 纸/4 墨/4 皮革；不是普通世界自动生产。若创建中断，只核对，不重新投料。",ChurchRecord.Access.CONFIDENTIAL,Set.of(person),1,null,"church.fixture_intent"));
        var sourceDraft=new ChurchRecord(id(t.id(),"source"),t.id(),null,null,person,t.treasury().containerId(),now,"阶段8测试教材","灌溉知识示例：水渠需要连接实际农田。这是明确的测试教材，不授予科技点，也不保证所有地形都适用。",ChurchRecord.Access.PUBLIC_TEXT,Set.of(),.7,null,"church.source_text");
        box.setItem(slots.get(0),ChurchLifeService.book(sourceDraft));box.setItem(slots.get(1),new ItemStack(Items.PAPER,12));box.setItem(slots.get(2),new ItemStack(Items.INK_SAC,4));box.setItem(slots.get(3),new ItemStack(Items.LEATHER,4));box.setChanged();rights.bumpContext(t.treasury().containerId());
        var source=ChurchLifeService.catalogue(s,t,slots.get(0),ChurchRecord.Access.PUBLIC_TEXT);
        NpcBase clergy=actor(p,t,places.get(5),"阶段8主持者",TempleRecord.Role.CLERGY_OFFICIANT),teacher=actor(p,t,places.get(4),"阶段8教员",TempleRecord.Role.SCRIBE_TEACHER),care=actor(p,t,places.get(3),"阶段8修士/修女",TempleRecord.Role.COMMUNITY_RELIGIOUS),watch=actor(p,t,places.get(2),"阶段8看守",TempleRecord.Role.CHAPEL_WATCHMAN);
        NpcBase guest1=actor(p,t,places.get(0),"阶段8受邀者甲",null),guest2=actor(p,t,places.get(1),"阶段8受邀者乙",null);
        var guests=List.of(guest1.getCharacterId(),guest2.getCharacterId());
        add(p,t,"rite1",ChurchActivity.Kind.RITE,clergy,null,null,guests,2,0,true);
        add(p,t,"lesson",ChurchActivity.Kind.LESSON,teacher,null,source.id(),List.of(person),1,20,false);
        add(p,t,"copy",ChurchActivity.Kind.COPY,teacher,null,source.id(),List.of(),0,40,true);
        add(p,t,"visit",ChurchActivity.Kind.VISIT,care,guest1.getCharacterId(),null,List.of(guest1.getCharacterId()),1,60,true);
        add(p,t,"marriage",ChurchActivity.Kind.MARRIAGE,clergy,null,null,guests,2,80,true);
        add(p,t,"watch",ChurchActivity.Kind.WATCH,watch,null,null,List.of(),0,100,true);
        add(p,t,"maintenance",ChurchActivity.Kind.MAINTENANCE,watch,null,null,List.of(),0,120,true);
        UUID reported=UUID.randomUUID();KnowledgeStateData.get(s).testimony(clergy.getCharacterId(),person,new FactKey(reported,"church.death_report",""),"测试者明确提供的一份未核实死亡报告；不代表后台人物死亡真值。",PrecisionBand.CATEGORY,.6,now,0,DisclosurePolicy.PRIVATE);
        add(p,t,"funeral",ChurchActivity.Kind.FUNERAL,clergy,reported,null,guests,1,140,true);
        add(p,t,"rite2",ChurchActivity.Kind.RITE,clergy,null,null,guests,2,160,true);
        add(p,t,"rite3",ChurchActivity.Kind.RITE,clergy,null,null,guests,2,180,true);
        return "集中夹具已创建：新增六名测试 NPC、真实教材及 12 纸/4 墨/4 皮革；原人物、方块和物品未覆盖。先用 /saga church here 明确同意教学并留在接待处；约一分钟后看 demo-status，再保存退出并重进核对。";}
    private static void add(ServerPlayer p,TempleRecord t,String key,ChurchActivity.Kind kind,NpcBase host,UUID subject,UUID source,List<UUID> people,int minimum,int delay,boolean npcConsent){long now=p.level().getGameTime();var a=ChurchLifeService.schedule(p.level().getServer(),id(t.id(),key),t,kind,t.counter(),subject,source,people,minimum,now+delay,now+delay+4800,40).assign(host.getCharacterId());if(npcConsent)for(var id:people)a=a.accept(id);ChurchLifeData.get(p.level().getServer()).put(a);}
    private static NpcBase actor(ServerPlayer p,TempleRecord t,BlockPos pos,String name,TempleRecord.Role role){var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),p.level());n.setCharacterId(UUID.randomUUID());n.setNpcName(name);n.setProfession(NpcProfession.BARD);n.setPersistenceRequired();n.snapTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);n.setCommand(role==null?NpcCommand.STAY:NpcCommand.WORK);p.level().addFreshEntity(n);
        var d=CharacterRegistryData.get(p.level().getServer());d.ensureNpcCharacter(n.getCharacterId(),name,n.getSocialClass(),n.getOffice(),"sagadyssey:church",p.level().getGameTime());d.bindNpcBody(n.getCharacterId(),p.level().dimension().identifier().toString(),n.getUUID(),p.level().getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);if(role!=null)ChapelBootstrapService.appoint(p.level().getServer(),t,SubjectRef.institution(t.id()),n,role);return n;}
    public static List<String> status(ServerPlayer p,TempleRecord t){UUID person=ReligionService.currentCharacter(p);if(person==null)return List.of("当前没有可用的存活人物身份。");var d=ChurchLifeData.get(p.level().getServer());var lines=new ArrayList<String>();for(var key:JOBS){UUID id=id(t.id(),key);var a=d.activities().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);lines.add(key+"："+(a==null?"尚未创建，可能创建中断须核对":a.stage()+" "+a.work()+"/"+a.duration()+" "+a.detail()));}
        UUID lesson=id(t.id(),"lesson");lines.add("本人的实际教学时长="+d.exposure(lesson,person)+"/40；只有真正听完课程才取得教材知识。");return lines;}
}
