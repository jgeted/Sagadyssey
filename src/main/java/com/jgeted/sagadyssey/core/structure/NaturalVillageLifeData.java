package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** Durable initialization intent. Missing chunks or facilities wait; existing/dead people are never respawned. */
public final class NaturalVillageLifeData extends NbtSavedData {
    private static final String NAME="sagadyssey_natural_village_life_v1";
    private static final Factory<NaturalVillageLifeData> FACTORY=new Factory<>(NaturalVillageLifeData::new,NaturalVillageLifeData::load);
    private final Map<String,CompoundTag> villages=new LinkedHashMap<>();
    private int cursor;
    private final Map<String,CompoundTag> payments=new HashMap<>();
    private final Set<UUID> individualResidents=new HashSet<>();
    private final Map<UUID,Set<Long>> cultivatedColumns=new HashMap<>();
    private final Map<String,Map<Long,UUID>> managedStores=new HashMap<>();
    public static UUID managedStore(net.minecraft.server.MinecraftServer server,String dimension,BlockPos pos){var d=existing(server);return d==null?null:d.managedStores.getOrDefault(dimension,Map.of()).get(pos.asLong());}
    private void indexResidents(NaturalVillageBuilding b){
        for(int i=0;i<3;i++)individualResidents.add(person(b,i));
        var columns=new HashSet<Long>();
        // Exact block columns, not chunk ownership. Surrounding hydrated soil is not an extra seeded plot.
        for(var p:NaturalVillageLayout.crops()){var world=NaturalVillageLayout.at(b,p);columns.add(BlockPos.asLong(world.getX(),0,world.getZ()));}
        cultivatedColumns.put(person(b,0),Set.copyOf(columns));
        var positions=NaturalVillageLayout.stores(b);
        for(int i=0;i<positions.size();i++){var p=NaturalVillageLayout.at(b,positions.get(i));managedStores.computeIfAbsent(b.dimension(),k->new HashMap<>()).put(p.asLong(),ContainerIds.block(NaturalVillageLayout.storageRole(i),b.dimension(),p));}
    }
    public static boolean cultivationAllowed(NpcBase npc,BlockPos position){
        if(npc.getProfession()!=NpcProfession.FARMER)return true;var data=existing(MinecraftApi.server(npc));if(data==null)return true;
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.residentVillage(MinecraftApi.server(npc),npc.getCharacterId())!=null)
            return com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.cultivation(npc,position);
        var columns=data.cultivatedColumns.get(npc.getCharacterId());return columns==null||columns.contains(BlockPos.asLong(position.getX(),0,position.getZ()))||com.jgeted.sagadyssey.social.growth.SettlementGrowthData.extraField(npc,position);
    }
    public static NaturalVillageLifeData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(NAME));}
    public static NaturalVillageLifeData existing(MinecraftServer server){return server.overworld().getDataStorage().get(FACTORY.type(NAME));}
    public List<CompoundTag> villages(){return villages.values().stream().map(CompoundTag::copy).toList();}
    public CompoundTag village(String key){var v=villages.get(key);return v==null?null:v.copy();}
    public static UUID body(NaturalVillageBuilding b,int index){return ContainerIds.named("natural-body:v1:"+b.buildingKey()+":"+index);}
    public static UUID person(NaturalVillageBuilding b,int index){return ContainerIds.named("natural-person:v1:"+b.buildingKey()+":"+index);}
    public static UUID home(NaturalVillageBuilding b){return ContainerIds.named("natural-household:v1:"+b.buildingKey());}
    public static boolean individualRations(MinecraftServer server,UUID person){
        var d=existing(server);if(d==null)return false;
        return d.individualResidents.contains(person);
    }
    public static boolean ordinaryRawDiet(com.jgeted.sagadyssey.npc.entity.NpcBase npc){return individualRations(MinecraftApi.server(npc),npc.getCharacterId());}
    public void schedule(NaturalVillageBuilding b){
        if(!NaturalVillageLayout.supportedTemplate(b.templateId())||NaturalVillageBuilding.SUPPORTED.toString().equals(b.templateId())||villages.containsKey(b.buildingKey()))return;
        if(villages.size()>=4096)throw new IllegalStateException("Natural village registry full");
        var v=new CompoundTag();v.putString("Dimension",b.dimension());v.putString("Structure",b.structureId());
        v.putLong("Start",b.startChunkOrigin().asLong());v.putLong("Center",b.settlementCenter().asLong());
        v.putString("Template",b.templateId());v.putLong("Origin",b.buildingOrigin().asLong());v.putString("Rotation",b.rotation().name());
        v.putInt("DefinitionVersion",NaturalVillageLayout.chartered(b)?2:1);v.putString("EconomyKind",NaturalVillageLayout.woodcamp(b)?"forestry_imports_food":"carrot_farming");
        v.putString("Reason","等待实际设施和区块");villages.put(b.buildingKey(),v);setDirty();
    }
    public static NaturalVillageBuilding decode(CompoundTag v){return new NaturalVillageBuilding(v.getStringOr("Dimension", ""),v.getStringOr("Structure", ""),
            BlockPos.of(v.getLongOr("Start", 0L)),BlockPos.of(v.getLongOr("Center", 0L)),v.getStringOr("Template", ""),BlockPos.of(v.getLongOr("Origin", 0L)),Rotation.valueOf(v.getStringOr("Rotation", "")));}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(event.getServer().overworld().getGameTime()%100!=0)return;
        var d=existing(event.getServer());if(d==null||d.villages.isEmpty())return;
        var entries=new ArrayList<>(d.villages.values());
        for(int n=0;n<Math.min(4,entries.size());n++){
            var v=entries.get(Math.floorMod(d.cursor++,entries.size()));if(v.getBooleanOr("Ready", false))continue;
            d.initialize(event.getServer(),v);
        }
    }
    private void waitFor(CompoundTag v,String reason){if(!reason.equals(v.getStringOr("Reason", ""))){v.putString("Reason",reason);setDirty();}}
    private void initialize(MinecraftServer s,CompoundTag v){
        var b=decode(v);ServerLevel l=null;for(var level:s.getAllLevels())if(level.dimension().identifier().toString().equals(b.dimension()))l=level;
        if(l==null){waitFor(v,"维度不可用");return;}
        var necessary=new ArrayList<>(List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC));
        necessary.addAll(NaturalVillageLayout.initialBeds(b));necessary.addAll(NaturalVillageLayout.SPAWNS);necessary.addAll(NaturalVillageLayout.crops());
        for(var p:necessary)if(!l.hasChunkAt(NaturalVillageLayout.at(b,p))){waitFor(v,"生活设施所在区块尚未加载");return;}
        for(var p:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC))
            if(!(l.getBlockEntity(NaturalVillageLayout.at(b,p)) instanceof net.minecraft.world.Container)){waitFor(v,"缺少实际储物设施");return;}
        for(var p:NaturalVillageLayout.initialBeds(b))if(!l.getBlockState(NaturalVillageLayout.at(b,p)).is(net.minecraft.tags.BlockTags.BEDS)){waitFor(v,"缺少实际床位");return;}
        for(var p:NaturalVillageLayout.crops())if(!l.getBlockState(NaturalVillageLayout.at(b,p.below())).is(Blocks.FARMLAND)){waitFor(v,"缺少合法耕地");return;}
        NpcBase[] npcs=new NpcBase[3];var characters=CharacterRegistryData.get(s);
        for(int i=0;i<3;i++){
            var e=l.getEntity(body(b,i));
            if(e instanceof NpcBase n)npcs[i]=n;
            else if(characters.byId(person(b,i))!=null||v.getBooleanOr("Spawned"+i, false)){waitFor(v,"原居民死亡或未加载，保留身份而不重造");return;}
            else {
                var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setUUID(body(b,i));n.setCharacterId(person(b,i));
                n.setNpcName(new String[]{"村庄农民","村庄运输者","村庄林工"}[i]);n.setProfession(new NpcProfession[]{NaturalVillageLayout.woodcamp(b)?NpcProfession.WORKER:NpcProfession.FARMER,NpcProfession.COURIER,NpcProfession.WORKER}[i]);
                var p=NaturalVillageLayout.at(b,NaturalVillageLayout.SPAWNS.get(i));n.snapTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);
                n.setPersistenceRequired();n.setHomeSettlementId(b.settlementId());n.setCommand(NpcCommand.STAY);
                n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
                n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,ItemStack.EMPTY);
                if(i==0){n.setFarmMode(FarmMode.CROP);n.getPersonalInventory().setItem(0,new ItemStack(Items.CARROT,NaturalVillageLayout.autonomousFarm(b)?12:16));}if(i==0&&NaturalVillageLayout.woodcamp(b))n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_AXE));
                if(i==2)n.getPersonalInventory().setItem(0,new ItemStack(Items.IRON_AXE));
                if(com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(b)){
                    if(i==0)n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_HOE));
                    if(i==2)n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));
                }
                if(!l.addFreshEntity(n)){waitFor(v,"居民实体身份冲突");return;}npcs[i]=n;
            }
            v.putBoolean("Spawned"+i,true);setDirty();
        }
        long now=l.getGameTime();var houses=HouseholdData.get(s);var id=home(b);var pantry=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);
        var household=houses.createWithId(id,"村庄生活户",b.dimension(),b.settlementId(),person(b,1),pantry,now);
        if(household==null){waitFor(v,"家庭身份冲突");return;}
        for(int i:List.of(0,2))if(!houses.addMember(id,person(b,i),HouseholdRole.KIN,now)){waitFor(v,"居民已有其他家庭");return;}
        if(!houses.assignSharedStorage(id,b.dimension(),pantry,pantry)){waitFor(v,"共享粮仓已属于其他家庭");return;}
        // The explicit exact-item policy below is the complete direct-carrot diet, with no fictitious bread/fuel needs.
        houses.setProvisionPlan(new HouseholdProvisionPlan(id,0,0,0));
        var lives=NpcLifeData.get(s);
        for(int i=0;i<3;i++)lives.registerInstitutional(npcs[i],b.dimension(),b.settlementId(),NaturalVillageLayout.at(b,NaturalVillageLayout.SPAWNS.get(i)),
                "村庄住宅",NaturalVillageLayout.initialBed(b,i),null,NpcWorkShift.DAY,now,true);
        if(NaturalVillageLayout.woodcamp(b))npcs[0].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(14,1,14)),NaturalVillageLayout.at(b,new BlockPos(16,3,18)));
        else npcs[0].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(14,1,4)),NaturalVillageLayout.at(b,new BlockPos(20,1,8)));
        if(NaturalVillageLayout.woodcamp(b))npcs[2].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(18,1,14)),NaturalVillageLayout.at(b,new BlockPos(20,3,18)));
        else npcs[2].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(16,1,16)),NaturalVillageLayout.at(b,new BlockPos(20,3,18)));
        if(com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(b)){
            npcs[0].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(14,1,4)),NaturalVillageLayout.at(b,new BlockPos(21,1,8)));
            npcs[2].setWorkZone(NaturalVillageLayout.at(b,new BlockPos(18,1,15)),NaturalVillageLayout.at(b,new BlockPos(24,3,19)));
        }
        if(!lives.assignPrivateStorage(s,person(b,0),b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.RAW),now)
                ||!lives.assignPrivateStorage(s,person(b,2),b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.WOOD),now)){waitFor(v,"私人仓储权属冲突");return;}
        var principal=SubjectRef.household(id);var rights=RightsStateData.get(s);var flows=SupplyFlowData.get(s);
        for(int i:List.of(0,2)){
            var p=NaturalVillageLayout.at(b,i==0?NaturalVillageLayout.RAW:NaturalVillageLayout.WOOD);var owner=SubjectRef.character(person(b,i));
            UUID store=ContainerIds.block("private_storage",b.dimension(),p);
            rights.ensureContext(store,b.dimension()+":"+p.asLong(),owner,owner,AccessPolicy.PRIVATE);
            flows.register(new FieldworkStorage(b.dimension(),p,store,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"natural-household-source"));flows.reveal(principal,store);
            if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.industry(b))flows.reveal(owner,store);
            rights.grant(new RightGrant(ContainerIds.named("natural-source:"+id+":"+store),store,principal,Set.of(AssetRight.TRANSFER),"生成时成员同意向生活户供给",now,null,true));
        }
        UUID destination=ContainerIds.block("household_storage",b.dimension(),pantry);
        rights.ensureContext(destination,b.dimension()+":"+pantry.asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
        flows.register(new FieldworkStorage(b.dimension(),pantry,destination,principal,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"natural-pantry"));flows.reveal(principal,destination);
        flows.policy(new SupplyFlowData.Policy("natural-diet:"+id,principal,destination,new ItemFingerprint("minecraft:carrot",0),com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(b)?64:16,"household"));
        if(NaturalVillageLayout.starterHomestead(b)&&!com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(b))
            flows.policy(new SupplyFlowData.Policy("natural-housing-timber:"+id,principal,destination,new ItemFingerprint("minecraft:oak_log",0),1,"household"));
        var publicPos=NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC);UUID publicId=ContainerIds.block("natural_public",b.dimension(),publicPos);
        var publicOwner=SubjectRef.institution(b.settlementId());rights.ensureContext(publicId,b.dimension()+":"+publicPos.asLong(),publicOwner,publicOwner,AccessPolicy.PRIVATE);
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.industry(b)){
            var owner=SubjectRef.character(person(b,2));
            registerFoundingStation(l,b,NaturalVillageLayout.INDUSTRY_BENCH,Blocks.CRAFTING_TABLE,owner,rights,flows);
            registerFoundingStation(l,b,NaturalVillageLayout.INDUSTRY_FURNACE,Blocks.FURNACE,owner,rights,flows);
        }
        v.putBoolean("Ready",true);v.putString("Reason","自然生成生活前置条件已建立");v.putLong("Initialized",now);setDirty();
        indexResidents(b);
        for(var n:npcs){n.setCommand(NpcCommand.WORK);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);}
    }
    private static void registerFoundingStation(ServerLevel level,NaturalVillageBuilding building,BlockPos relative,
                                                net.minecraft.world.level.block.Block block,SubjectRef owner,
                                                RightsStateData rights,SupplyFlowData flows){
        var pos=NaturalVillageLayout.at(building,relative);
        if(!level.hasChunkAt(pos)||!level.getBlockState(pos).is(block))return;
        var dimension=building.dimension();
        var id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(level.getServer(),dimension,pos);
        var title=rights.context(id);
        if(title!=null&&(!title.defaultBeneficialOwner().equals(owner)||!title.custodian().equals(owner)
                ||title.accessPolicy()!=AccessPolicy.PRIVATE))return;
        var point=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"founding-industry-station");
        var existing=flows.pointOrNull(id);
        if(existing!=null&&!existing.equals(point))return;
        rights.ensureContext(id,dimension+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
        flows.register(point);flows.reveal(owner,id);
    }
    public CompoundTag payment(UUID character,UUID settlement){var p=payments.get(character+"/"+settlement);return p==null?null:p.copy();}
    /** A standing work offer reserves no inventory. Execution keeps the original durable transaction. */
    public UUID acceptWork(UUID character,UUID settlement,long day,String building,UUID household){
        String key=character+"/"+settlement;var previous=payments.get(key);
        if(previous!=null){
            String state=previous.getStringOr("State", "");
            if(state.equals("PENDING")||state.equals("REVIEW")||state.equals("PAID")&&previous.getLongOr("Day", 0L)>=day)return null;
            if(state.equals("ACCEPTED"))return building.equals(previous.getStringOr("Building", ""))&&household.equals(previous.read("Household", UUIDUtil.CODEC).orElseThrow())?previous.read("Transaction", UUIDUtil.CODEC).orElseThrow():null;
        }
        var p=new CompoundTag();p.putString("Key",key);p.store("Transaction", UUIDUtil.CODEC,UUID.randomUUID());p.putLong("Day",day);p.putString("State","ACCEPTED");
        p.putString("Building",building);p.store("Household", UUIDUtil.CODEC,household);payments.put(key,p);setDirty();return p.read("Transaction", UUIDUtil.CODEC).orElseThrow();
    }
    public boolean cancelWork(UUID character,UUID settlement){
        var p=payments.get(character+"/"+settlement);if(p==null||!p.getStringOr("State", "").equals("ACCEPTED"))return false;
        p.putString("State","CANCELLED");p.putString("Reason","成员取消未提交任务；未预留、扣除或支付物品");setDirty();return true;
    }
    public UUID beginPayment(UUID character,UUID settlement,long day){
        String key=character+"/"+settlement;var previous=payments.get(key);
        if(previous!=null&&(previous.getStringOr("State", "").equals("PENDING")||previous.getStringOr("State", "").equals("REVIEW")||previous.getStringOr("State", "").equals("PAID")&&previous.getLongOr("Day", 0L)>=day))return null;
        if(previous!=null&&previous.getStringOr("State", "").equals("ACCEPTED")){
            previous.putString("State","PENDING");previous.putLong("Day",day);setDirty();return previous.read("Transaction", UUIDUtil.CODEC).orElseThrow();
        }
        var p=new CompoundTag();p.putString("Key",key);p.store("Transaction", UUIDUtil.CODEC,UUID.randomUUID());p.putLong("Day",day);p.putString("State","PENDING");payments.put(key,p);setDirty();return p.read("Transaction", UUIDUtil.CODEC).orElseThrow();
    }
    public void finishPayment(UUID character,UUID settlement,String state){
        if(!Set.of("PAID","FAILED","REVIEW").contains(state))throw new IllegalArgumentException("Payment result");
        var p=payments.get(character+"/"+settlement);if(p==null||!p.getStringOr("State", "").equals("PENDING"))throw new IllegalStateException("No pending payment");
        p.putString("State",state);setDirty();
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){var list=new ListTag();villages.values().forEach(v->list.add(v.copy()));t.put("Villages",list);
        var paid=new ListTag();payments.values().forEach(v->paid.add(v.copy()));t.put("Payments",paid);return t;}
    static NaturalVillageLifeData load(CompoundTag t,HolderLookup.Provider lookup){var d=new NaturalVillageLifeData();for(var e:MinecraftApi.listOfType(t, "Villages",Tag.TAG_COMPOUND)){
        var v=((CompoundTag)e).copy();var b=decode(v);String key=b.buildingKey();if(d.villages.putIfAbsent(key,v)!=null)throw new IllegalArgumentException("Duplicate village life identity");if(v.getBooleanOr("Ready", false))d.indexResidents(b);}
        for(var e:MinecraftApi.listOfType(t, "Payments",Tag.TAG_COMPOUND)){var v=((CompoundTag)e).copy();if(d.payments.putIfAbsent(v.getStringOr("Key", ""),v)!=null)throw new IllegalArgumentException("Duplicate payment");}return d;}
}
