package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Explicit OP-only consolidated client fixture. No old actor, building or inventory is overwritten. */
@EventBusSubscriber(modid="sagadyssey")
public final class NpcCombatClientFixture {
    private static final String NAME="sagadyssey_phase9_client_fixture";
    public static final class Data extends NbtSavedData {
        private static final Factory<Data> FACTORY=new Factory<>(Data::new,Data::load);
        private final Map<UUID,CompoundTag> records=new HashMap<>();
        static Data existing(MinecraftServer s){var d=s.overworld().getDataStorage().get(FACTORY.type(NAME));
            if(d==null&&java.nio.file.Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable explicit client fixture");return d;}
        static Data get(MinecraftServer s){var d=existing(s);if(d!=null)return d;d=new Data();s.overworld().getDataStorage().set(FACTORY.type(NAME),d);return d;}
        CompoundTag find(UUID person){var row=records.get(person);return row==null?null:row.copy();}
        void put(UUID person,CompoundTag row){if(!records.containsKey(person)&&records.size()>=64)throw new IllegalStateException("client fixture capacity reached");records.put(person,row.copy());setDirty();}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("Schema",1);var l=new ListTag();records.forEach((id,row)->{var r=row.copy();r.store("Person", UUIDUtil.CODEC,id);l.add(r);});tag.put("Fixtures",l);return tag;}
        static Data load(CompoundTag tag,HolderLookup.Provider lookup){
            if(!MinecraftApi.containsTag(tag, "Schema",Tag.TAG_INT)||tag.getIntOr("Schema", 0)!=1||!(tag.get("Fixtures") instanceof ListTag l)||l.size()>64||!l.isEmpty()&&MinecraftApi.elementType(l)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid client fixture ledger");
            var d=new Data();
            for(var raw:l){
                var r=(CompoundTag)raw;
                if(!r.read("Person", UUIDUtil.CODEC).isPresent()||!MinecraftApi.containsTag(r, "Dimension",Tag.TAG_STRING)||!MinecraftApi.containsTag(r, "Anchor",Tag.TAG_LONG)||!MinecraftApi.containsTag(r, "Created",Tag.TAG_LONG)||r.getLongOr("Created", 0L)<0||!MinecraftApi.containsTag(r, "State",Tag.TAG_STRING)||!Set.of("INTENT","READY").contains(r.getStringOr("State", ""))||d.records.containsKey(r.read("Person", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("invalid client fixture identity");
                net.minecraft.resources.Identifier.parse(r.getStringOr("Dimension", ""));
                if(r.getStringOr("State", "").equals("READY")){
                    for(var field:List.of("Smith","Doctor","Patient","Guard","Carrier","Order","Medical","Need"))if(!r.read(field, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing original client fixture object");
                    if(!MinecraftApi.containsTag(r, "Box",Tag.TAG_LONG)||!MinecraftApi.containsTag(r, "Destination",Tag.TAG_LONG)||!MinecraftApi.containsTag(r, "Interrupted",Tag.TAG_BYTE)||r.contains("Withdrawal")&&!r.read("Withdrawal", UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("invalid client fixture recovery marker");
                }
                d.put(r.read("Person", UUIDUtil.CODEC).orElseThrow(),r);
            }
            return d;
        }
    }
    public static String prepare(ServerPlayer p){UUID person=CharacterRegistry.currentId(p);if(!p.isAlive()||person==null)return "当前没有可用的在生人物；完成正式进入或重生后再准备夹具。";var data=Data.get(p.level().getServer());if(data.find(person)!=null)return "原集中夹具已有创建意图，不重复生成人物或物资。用 /saga combat demo-status 核对。";
        var level=p.level();var anchor=p.blockPosition().offset(2,0,2);
        for(int x=0;x<=18;x++)for(int z=0;z<=12;z++){var pos=anchor.offset(x,0,z);if(!level.hasChunkAt(pos)||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir()||!level.getBlockState(pos.below()).isCollisionShapeFullBlock(level,pos.below()))throw new IllegalStateException("请在平坦空地上运行；前方需 19×13 格空站位和实体地面。不铺地、不拆除已有建筑。阻塞="+pos+"，脚下="+level.getBlockState(pos.below())+"，站位="+level.getBlockState(pos)+"，上方="+level.getBlockState(pos.above()));}
        long now=level.getGameTime();var tag=new CompoundTag();tag.putString("Dimension",level.dimension().identifier().toString());tag.putLong("Anchor",anchor.asLong());tag.putString("State","INTENT");tag.putLong("Created",now);data.put(person,tag);
        // Record intent before creating anything; save uncertainty requires review, never automatic recreation.
        var smith=actor(p,anchor.offset(5,0,3),NpcProfession.BLACKSMITH,"阶段9测试铁匠");var doctor=actor(p,anchor.offset(4,0,8),NpcProfession.MEDIC,"阶段9测试医师");var patient=actor(p,anchor.offset(6,0,8),NpcProfession.WARRIOR,"阶段9测试患者");patient.setNoAi(true);
        var carrier=actor(p,anchor.offset(9,0,2),NpcProfession.COURIER,"阶段9测试运输员");var guard=actor(p,anchor.offset(16,0,5),NpcProfession.WARRIOR,"阶段9测试护卫");
        var groups=CommandGroupAttachments.get(p);var selection=groups.selectedMembers();var active=groups.activeSquadId();groups.clearSelectedMembers();for(var n:List.of(guard,carrier)){groups.registerMember(n.getCharacterId(),n.getNpcName());groups.select(n.getCharacterId());}groups.createSquadFromSelection();for(int i=0;i<8&&groups.activeStrategy()!=SquadStrategy.ESCORT_CONVOY;i++)groups.cycleActiveStrategy();groups.clearSelectedMembers();selection.forEach(groups::select);for(int i=0;i<groups.squadIds().size()&&!groups.activeSquadId().equals(active);i++)groups.cycleActiveSquad();
        var owner=SubjectRef.character(person);var boxPos=anchor.offset(3,0,3);level.setBlockAndUpdate(boxPos,Blocks.BARREL.defaultBlockState());var box=(Container)level.getBlockEntity(boxPos);box.setItem(0,new ItemStack(Items.IRON_INGOT,3));box.setItem(1,new ItemStack(Items.COAL));var container=ProductionContainerIds.resolve(p.level().getServer(),tag.getStringOr("Dimension", ""),boxPos);RightsStateData.get(p.level().getServer()).ensureContext(container,"explicit-p9-fixture:"+boxPos.asLong(),owner,owner,AccessPolicy.CONTRACT);
        var station=anchor.offset(6,0,3);level.setBlockAndUpdate(station,Blocks.ANVIL.defaultBlockState());var order=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:test_iron_pickaxe",List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)),3),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)),1)),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)),1),400,UUID.randomUUID(),tag.getStringOr("Dimension", ""),station,container,tag.getStringOr("Dimension", ""),boxPos,now,"测试者显式投料的真实订单；将有限撤离一次再续接");ProductionOrderData.get(p.level().getServer()).put(order);NpcBrainRuntime.createBlacksmithOrderPlan(smith,order.id());
        doctor.getPersonalInventory().setItem(0,new ItemStack(Items.WHITE_WOOL));var wound=InjuryService.recordHit(InjuryData.get(p.level().getServer()),UUID.randomUUID(),patient.getCharacterId(),null,WoundType.CUT,6,0,now,null);
        var medical=new NpcDutyData.Duty(UUID.randomUUID(),doctor.getUUID(),doctor.getCharacterId(),patient.getUUID(),patient.getCharacterId(),NpcDutyData.Kind.MEDICAL,wound.id(),List.of(),tag.getStringOr("Dimension", ""),patient.blockPosition(),400,now,now+24000,0,now,NpcDutyData.Status.OPEN,"测试者显式患者同意与测试伤口；仍需实际在场劳动及一份实体绷带");NpcDutyEvidenceData.get(p.level().getServer()).put(new NpcDutyEvidenceData.Evidence(medical.id(),medical.kind(),wound.id(),patient.getCharacterId(),doctor.getCharacterId(),patient.getCharacterId(),medical.expires(),false));NpcDutyData.get(p.level().getServer()).put(medical);
        var source=point(level,anchor.offset(10,0,2),owner);var destination=point(level,anchor.offset(17,0,2),owner);((Container)level.getBlockEntity(source.position())).setItem(0,new ItemStack(Items.COAL,3));SupplyFlowData.get(p.level().getServer()).reveal(owner,source.containerId());var need=SupplyFlowService.request(p.level().getServer(),owner,"phase9 explicit client convoy",destination,ItemFingerprint.of(new ItemStack(Items.COAL)),3,now,now+24000);SupplyFlowService.match(p.level().getServer(),now);
        for(var entry:Map.of("Smith",smith,"Doctor",doctor,"Patient",patient,"Guard",guard,"Carrier",carrier).entrySet())tag.store(entry.getKey(), UUIDUtil.CODEC,entry.getValue().getUUID());tag.store("Order", UUIDUtil.CODEC,order.id());tag.store("Medical", UUIDUtil.CODEC,medical.id());tag.store("Need", UUIDUtil.CODEC,need.id());tag.putLong("Box",boxPos.asLong());tag.putLong("Destination",destination.position().asLong());tag.putBoolean("Interrupted",false);tag.putString("State","READY");data.put(person,tag);
        return "集中夹具已创建：5 名新测试 NPC、真实订单原料、1 绷带和 3 煤运输。约一分钟观察锻造→有限撤离→同一订单恢复、当面治疗和护送交付。用 /saga combat demo-status 查看，然后保存退出并重进再核对；不修改旧人物、旧教堂或旧库存。";
    }
    private static NpcBase actor(ServerPlayer p,BlockPos pos,NpcProfession role,String name){var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),p.level());n.setCharacterId(UUID.randomUUID());n.setNpcName(name);n.setProfession(role);n.getPersonalInventory().clearContent();n.setPersistenceRequired();n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);n.setOwner(p);n.setCommand(NpcCommand.WORK);p.level().addFreshEntity(n);var chars=CharacterRegistryData.get(p.level().getServer());chars.ensureNpcCharacter(n.getCharacterId(),name,n.getSocialClass(),n.getOffice(),"sagadyssey:wilderness",p.level().getGameTime());chars.bindNpcBody(n.getCharacterId(),p.level().dimension().identifier().toString(),n.getUUID(),p.level().getGameTime());NpcLifeData.get(p.level().getServer()).onRecruited(n,CharacterRegistry.currentId(p),p.level().getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static FieldworkStorage point(ServerLevel level,BlockPos pos,SubjectRef owner){level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());String dimension=level.dimension().identifier().toString();var id=ContainerIds.block("physical_storage",dimension,pos);var point=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"explicit stage9 client supply");RightsStateData.get(level.getServer()).ensureContext(id,"explicit-p9-fixture:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);SupplyFlowData.get(level.getServer()).register(point);return point;}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var server=event.getServer();if(server.overworld().getGameTime()%20!=0)return;for(var p:server.getPlayerList().getPlayers()){
        if(!p.isAlive())continue;var person=CharacterRegistry.currentId(p);if(person==null)continue;
        var data=Data.existing(server);if(data==null)continue;var tag=data.find(person);if(tag==null||!tag.getStringOr("State", "").equals("READY")||tag.getBooleanOr("Interrupted", false)||!tag.getStringOr("Dimension", "").equals(p.level().dimension().identifier().toString()))continue;
        var body=p.level().getEntity(tag.read("Smith", UUIDUtil.CODEC).orElseThrow());if(!(body instanceof NpcBase smith)||ProductionLaborData.get(server).worked(tag.read("Order", UUIDUtil.CODEC).orElseThrow())<40)continue;
        tag.putBoolean("Interrupted",true);data.put(person,tag);if(!NpcDutyTask.requestWithdrawal(p,smith)){tag.putString("Failure","有限撤离请求未通过原授权");data.put(person,tag);continue;}var duty=NpcDutyData.get(server).active(smith.getUUID());tag.store("Withdrawal", UUIDUtil.CODEC,duty.id());data.put(person,tag);
    }}
    public static List<String> status(ServerPlayer p){var person=CharacterRegistry.currentId(p);if(!p.isAlive()||person==null)return List.of("当前没有可用的在生人物；查询不会创建人物或夹具。");var data=Data.existing(p.level().getServer());var t=data==null?null:data.find(person);if(t==null)return List.of("尚未创建本人的集中测试夹具");if(!t.getStringOr("State", "").equals("READY"))return List.of("原创建意图未完成；不重复生成，需核对真实人物和库存");var s=p.level().getServer();var lines=new ArrayList<String>();lines.add("测试者后台；不是普通玩家已知信息。原创建时间="+t.getLongOr("Created", 0L)+"；失败="+t.getStringOr("Failure", ""));
        var order=ProductionOrderData.get(s).byId(t.read("Order", UUIDUtil.CODEC).orElseThrow()).orElseThrow();lines.add("锻造="+order.status()+" 实际劳动="+ProductionLaborData.get(s).worked(order.id())+"/400；材料预留="+RightsStateData.get(s).reservationsForToken("production:"+order.batchId(),p.level().getGameTime()).size());
        var treatment=NpcDutyData.get(s).find(t.read("Medical", UUIDUtil.CODEC).orElseThrow());lines.add("医疗="+treatment.status()+" 实际劳动="+treatment.worked()+"/400；固定治疗回执="+(InjuryData.get(s).treatment(treatment.id())!=null));
        var guard=p.level().getEntity(t.read("Guard", UUIDUtil.CODEC).orElseThrow());var escort=guard instanceof NpcBase n?NpcDutyData.get(s).last(n.getUUID(),NpcDutyData.Kind.ESCORT):null;lines.add("护送="+(escort==null?"尚未执行（核对原运输对象是否在可见范围）":escort.status()+" 原对象="+escort.targetBody()));
        lines.add("真实运输="+NeedRequestData.get(s).request(t.read("Need", UUIDUtil.CODEC).orElseThrow()).status()+" 交付="+NeedRequestData.get(s).request(t.read("Need", UUIDUtil.CODEC).orElseThrow()).delivered()+"/3");
        var withdraw=t.read("Withdrawal", UUIDUtil.CODEC).isPresent()?NpcDutyData.get(s).find(t.read("Withdrawal", UUIDUtil.CODEC).orElseThrow()):null;lines.add("有限撤离="+(withdraw==null?"待锻造达到40实际劳动刻":withdraw.status())+"；同一订单续接="+NpcInterruptionData.get(s).all().stream().anyMatch(r->r.candidate().equals("production-order:"+order.id())&&r.continuation()!=null));
        for(var key:List.of("Smith","Doctor","Patient","Guard","Carrier"))lines.add("原实体 "+key+"="+t.read(key, UUIDUtil.CODEC).orElseThrow()+"，当前加载="+(p.level().getEntity(t.read(key, UUIDUtil.CODEC).orElseThrow()) instanceof NpcBase));return lines;}
}
