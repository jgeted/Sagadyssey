package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.command.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Explicit OP-only consolidated client fixture. No old actor, building or inventory is overwritten. */
@EventBusSubscriber(modid="sagadyssey")
public final class NpcCombatClientFixture {
    private static final String NAME="sagadyssey_phase9_client_fixture";
    public static final class Data extends SavedData {
        private static final Factory<Data> FACTORY=new Factory<>(Data::new,Data::load);
        private final Map<UUID,CompoundTag> records=new HashMap<>();
        static Data existing(MinecraftServer s){var d=s.overworld().getDataStorage().get(FACTORY,NAME);
            if(d==null&&java.nio.file.Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable explicit client fixture");return d;}
        static Data get(MinecraftServer s){var d=existing(s);if(d!=null)return d;d=new Data();s.overworld().getDataStorage().set(NAME,d);return d;}
        CompoundTag find(UUID person){var row=records.get(person);return row==null?null:row.copy();}
        void put(UUID person,CompoundTag row){if(!records.containsKey(person)&&records.size()>=64)throw new IllegalStateException("client fixture capacity reached");records.put(person,row.copy());setDirty();}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("Schema",1);var l=new ListTag();records.forEach((id,row)->{var r=row.copy();r.putUUID("Person",id);l.add(r);});tag.put("Fixtures",l);return tag;}
        static Data load(CompoundTag tag,HolderLookup.Provider lookup){
            if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1||!(tag.get("Fixtures") instanceof ListTag l)||l.size()>64||!l.isEmpty()&&l.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid client fixture ledger");
            var d=new Data();
            for(var raw:l){
                var r=(CompoundTag)raw;
                if(!r.hasUUID("Person")||!r.contains("Dimension",Tag.TAG_STRING)||!r.contains("Anchor",Tag.TAG_LONG)||!r.contains("Created",Tag.TAG_LONG)||r.getLong("Created")<0||!r.contains("State",Tag.TAG_STRING)||!Set.of("INTENT","READY").contains(r.getString("State"))||d.records.containsKey(r.getUUID("Person")))throw new IllegalArgumentException("invalid client fixture identity");
                net.minecraft.resources.ResourceLocation.parse(r.getString("Dimension"));
                if(r.getString("State").equals("READY")){
                    for(var field:List.of("Smith","Doctor","Patient","Guard","Carrier","Order","Medical","Need"))if(!r.hasUUID(field))throw new IllegalArgumentException("missing original client fixture object");
                    if(!r.contains("Box",Tag.TAG_LONG)||!r.contains("Destination",Tag.TAG_LONG)||!r.contains("Interrupted",Tag.TAG_BYTE)||r.contains("Withdrawal")&&!r.hasUUID("Withdrawal"))throw new IllegalArgumentException("invalid client fixture recovery marker");
                }
                d.put(r.getUUID("Person"),r);
            }
            return d;
        }
    }
    public static String prepare(ServerPlayer p){UUID person=CharacterRegistry.currentId(p);if(!p.isAlive()||person==null)return "当前没有可用的在生人物；完成正式进入或重生后再准备夹具。";var data=Data.get(p.server);if(data.find(person)!=null)return "原集中夹具已有创建意图，不重复生成人物或物资。用 /saga combat demo-status 核对。";
        var level=p.serverLevel();var anchor=p.blockPosition().offset(2,0,2);
        for(int x=0;x<=18;x++)for(int z=0;z<=12;z++){var pos=anchor.offset(x,0,z);if(!level.hasChunkAt(pos)||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir()||!level.getBlockState(pos.below()).isCollisionShapeFullBlock(level,pos.below()))throw new IllegalStateException("请在平坦空地上运行；前方需 19×13 格空站位和实体地面。不铺地、不拆除已有建筑。阻塞="+pos+"，脚下="+level.getBlockState(pos.below())+"，站位="+level.getBlockState(pos)+"，上方="+level.getBlockState(pos.above()));}
        long now=level.getGameTime();var tag=new CompoundTag();tag.putString("Dimension",level.dimension().location().toString());tag.putLong("Anchor",anchor.asLong());tag.putString("State","INTENT");tag.putLong("Created",now);data.put(person,tag);
        // Record intent before creating anything; save uncertainty requires review, never automatic recreation.
        var smith=actor(p,anchor.offset(5,0,3),NpcProfession.BLACKSMITH,"阶段9测试铁匠");var doctor=actor(p,anchor.offset(4,0,8),NpcProfession.MEDIC,"阶段9测试医师");var patient=actor(p,anchor.offset(6,0,8),NpcProfession.WARRIOR,"阶段9测试患者");patient.setNoAi(true);
        var carrier=actor(p,anchor.offset(9,0,2),NpcProfession.COURIER,"阶段9测试运输员");var guard=actor(p,anchor.offset(16,0,5),NpcProfession.WARRIOR,"阶段9测试护卫");
        var groups=CommandGroupAttachments.get(p);var selection=groups.selectedMembers();var active=groups.activeSquadId();groups.clearSelectedMembers();for(var n:List.of(guard,carrier)){groups.registerMember(n.getCharacterId(),n.getNpcName());groups.select(n.getCharacterId());}groups.createSquadFromSelection();for(int i=0;i<8&&groups.activeStrategy()!=SquadStrategy.ESCORT_CONVOY;i++)groups.cycleActiveStrategy();groups.clearSelectedMembers();selection.forEach(groups::select);for(int i=0;i<groups.squadIds().size()&&!groups.activeSquadId().equals(active);i++)groups.cycleActiveSquad();
        var owner=SubjectRef.character(person);var boxPos=anchor.offset(3,0,3);level.setBlockAndUpdate(boxPos,Blocks.BARREL.defaultBlockState());var box=(Container)level.getBlockEntity(boxPos);box.setItem(0,new ItemStack(Items.IRON_INGOT,3));box.setItem(1,new ItemStack(Items.COAL));var container=ProductionContainerIds.resolve(p.server,tag.getString("Dimension"),boxPos);RightsStateData.get(p.server).ensureContext(container,"explicit-p9-fixture:"+boxPos.asLong(),owner,owner,AccessPolicy.CONTRACT);
        var station=anchor.offset(6,0,3);level.setBlockAndUpdate(station,Blocks.ANVIL.defaultBlockState());var order=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:test_iron_pickaxe",List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)),3),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)),1)),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)),1),400,UUID.randomUUID(),tag.getString("Dimension"),station,container,tag.getString("Dimension"),boxPos,now,"测试者显式投料的真实订单；将有限撤离一次再续接");ProductionOrderData.get(p.server).put(order);NpcBrainRuntime.createBlacksmithOrderPlan(smith,order.id());
        doctor.getPersonalInventory().setItem(0,new ItemStack(Items.WHITE_WOOL));var wound=InjuryService.recordHit(InjuryData.get(p.server),UUID.randomUUID(),patient.getCharacterId(),null,WoundType.CUT,6,0,now,null);
        var medical=new NpcDutyData.Duty(UUID.randomUUID(),doctor.getUUID(),doctor.getCharacterId(),patient.getUUID(),patient.getCharacterId(),NpcDutyData.Kind.MEDICAL,wound.id(),List.of(),tag.getString("Dimension"),patient.blockPosition(),400,now,now+24000,0,now,NpcDutyData.Status.OPEN,"测试者显式患者同意与测试伤口；仍需实际在场劳动及一份实体绷带");NpcDutyEvidenceData.get(p.server).put(new NpcDutyEvidenceData.Evidence(medical.id(),medical.kind(),wound.id(),patient.getCharacterId(),doctor.getCharacterId(),patient.getCharacterId(),medical.expires(),false));NpcDutyData.get(p.server).put(medical);
        var source=point(level,anchor.offset(10,0,2),owner);var destination=point(level,anchor.offset(17,0,2),owner);((Container)level.getBlockEntity(source.position())).setItem(0,new ItemStack(Items.COAL,3));SupplyFlowData.get(p.server).reveal(owner,source.containerId());var need=SupplyFlowService.request(p.server,owner,"phase9 explicit client convoy",destination,ItemFingerprint.of(new ItemStack(Items.COAL)),3,now,now+24000);SupplyFlowService.match(p.server,now);
        for(var entry:Map.of("Smith",smith,"Doctor",doctor,"Patient",patient,"Guard",guard,"Carrier",carrier).entrySet())tag.putUUID(entry.getKey(),entry.getValue().getUUID());tag.putUUID("Order",order.id());tag.putUUID("Medical",medical.id());tag.putUUID("Need",need.id());tag.putLong("Box",boxPos.asLong());tag.putLong("Destination",destination.position().asLong());tag.putBoolean("Interrupted",false);tag.putString("State","READY");data.put(person,tag);
        return "集中夹具已创建：5 名新测试 NPC、真实订单原料、1 绷带和 3 煤运输。约一分钟观察锻造→有限撤离→同一订单恢复、当面治疗和护送交付。用 /saga combat demo-status 查看，然后保存退出并重进再核对；不修改旧人物、旧教堂或旧库存。";
    }
    private static NpcBase actor(ServerPlayer p,BlockPos pos,NpcProfession role,String name){var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),p.serverLevel());n.setCharacterId(UUID.randomUUID());n.setNpcName(name);n.setProfession(role);n.getPersonalInventory().clearContent();n.setPersistenceRequired();n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);n.setOwner(p);n.setCommand(NpcCommand.WORK);p.serverLevel().addFreshEntity(n);var chars=CharacterRegistryData.get(p.server);chars.ensureNpcCharacter(n.getCharacterId(),name,n.getSocialClass(),n.getOffice(),"sagadyssey:wilderness",p.level().getGameTime());chars.bindNpcBody(n.getCharacterId(),p.level().dimension().location().toString(),n.getUUID(),p.level().getGameTime());NpcLifeData.get(p.server).onRecruited(n,CharacterRegistry.currentId(p),p.level().getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static FieldworkStorage point(ServerLevel level,BlockPos pos,SubjectRef owner){level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());String dimension=level.dimension().location().toString();var id=ContainerIds.block("physical_storage",dimension,pos);var point=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"explicit stage9 client supply");RightsStateData.get(level.getServer()).ensureContext(id,"explicit-p9-fixture:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);SupplyFlowData.get(level.getServer()).register(point);return point;}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var server=event.getServer();if(server.overworld().getGameTime()%20!=0)return;for(var p:server.getPlayerList().getPlayers()){
        if(!p.isAlive())continue;var person=CharacterRegistry.currentId(p);if(person==null)continue;
        var data=Data.existing(server);if(data==null)continue;var tag=data.find(person);if(tag==null||!tag.getString("State").equals("READY")||tag.getBoolean("Interrupted")||!tag.getString("Dimension").equals(p.level().dimension().location().toString()))continue;
        var body=p.serverLevel().getEntity(tag.getUUID("Smith"));if(!(body instanceof NpcBase smith)||ProductionLaborData.get(server).worked(tag.getUUID("Order"))<40)continue;
        tag.putBoolean("Interrupted",true);data.put(person,tag);if(!NpcDutyTask.requestWithdrawal(p,smith)){tag.putString("Failure","有限撤离请求未通过原授权");data.put(person,tag);continue;}var duty=NpcDutyData.get(server).active(smith.getUUID());tag.putUUID("Withdrawal",duty.id());data.put(person,tag);
    }}
    public static List<String> status(ServerPlayer p){var person=CharacterRegistry.currentId(p);if(!p.isAlive()||person==null)return List.of("当前没有可用的在生人物；查询不会创建人物或夹具。");var data=Data.existing(p.server);var t=data==null?null:data.find(person);if(t==null)return List.of("尚未创建本人的集中测试夹具");if(!t.getString("State").equals("READY"))return List.of("原创建意图未完成；不重复生成，需核对真实人物和库存");var s=p.server;var lines=new ArrayList<String>();lines.add("测试者后台；不是普通玩家已知信息。原创建时间="+t.getLong("Created")+"；失败="+t.getString("Failure"));
        var order=ProductionOrderData.get(s).byId(t.getUUID("Order")).orElseThrow();lines.add("锻造="+order.status()+" 实际劳动="+ProductionLaborData.get(s).worked(order.id())+"/400；材料预留="+RightsStateData.get(s).reservationsForToken("production:"+order.batchId(),p.level().getGameTime()).size());
        var treatment=NpcDutyData.get(s).find(t.getUUID("Medical"));lines.add("医疗="+treatment.status()+" 实际劳动="+treatment.worked()+"/400；固定治疗回执="+(InjuryData.get(s).treatment(treatment.id())!=null));
        var guard=p.serverLevel().getEntity(t.getUUID("Guard"));var escort=guard instanceof NpcBase n?NpcDutyData.get(s).last(n.getUUID(),NpcDutyData.Kind.ESCORT):null;lines.add("护送="+(escort==null?"尚未执行（核对原运输对象是否在可见范围）":escort.status()+" 原对象="+escort.targetBody()));
        lines.add("真实运输="+NeedRequestData.get(s).request(t.getUUID("Need")).status()+" 交付="+NeedRequestData.get(s).request(t.getUUID("Need")).delivered()+"/3");
        var withdraw=t.hasUUID("Withdrawal")?NpcDutyData.get(s).find(t.getUUID("Withdrawal")):null;lines.add("有限撤离="+(withdraw==null?"待锻造达到40实际劳动刻":withdraw.status())+"；同一订单续接="+NpcInterruptionData.get(s).all().stream().anyMatch(r->r.candidate().equals("production-order:"+order.id())&&r.continuation()!=null));
        for(var key:List.of("Smith","Doctor","Patient","Guard","Carrier"))lines.add("原实体 "+key+"="+t.getUUID(key)+"，当前加载="+(p.serverLevel().getEntity(t.getUUID(key)) instanceof NpcBase));return lines;}
}
