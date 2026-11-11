package com.jgeted.sagadyssey.social.industry;

import net.minecraft.world.entity.animal.sheep.Sheep;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import net.neoforged.neoforge.gametest.*;

/** Frozen finite sources, normal NPC work/transfer/consumption, isolated complete-process checkpoints. */
@GameTestHolder("sagadyssey_industries_ab") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class EntityFurnaceGameTests {
    private static final BlockPos BASE=new BlockPos(3072,80,3072);
    private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind TICKS=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("industry-ab-native");
    private static CompoundTag prior;
    private static String phase(){return System.getProperty("sagadyssey.industry.ab.phase","disabled");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.industry.native"))return List.of();
        return switch(phase()){
            case "wool-start" -> List.of(test("wool_checkpoint",EntityFurnaceGameTests::woolStart,24000));
            case "wool-resume" -> List.of(test("wool_to_two_beds",EntityFurnaceGameTests::woolResume,24000));
            case "fish" -> List.of(test("two_fish_and_actual_meal",EntityFurnaceGameTests::fish,12000));
            case "smelt-start" -> List.of(test("smelt_checkpoint",EntityFurnaceGameTests::smeltStart,16000));
            case "smelt-resume" -> List.of(test("ore_to_tool_to_original_miner",EntityFurnaceGameTests::smeltResume,16000));
            case "guards" -> List.of(test("native_authority_and_no_effect_guards",EntityFurnaceGameTests::guards,250));
            default -> List.of();
        };
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks){return new TestFunction("industry_ab","industry_ab."+name,"sagadyssey_industries_ab:empty",ticks,0,true,body);}
    @net.neoforged.bus.api.SubscribeEvent public static void boot(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        prior=null;if(!(e.getServer() instanceof GameTestServer)||!Boolean.getBoolean("sagadyssey.industry.native"))return;
        try{
            var s=e.getServer();s.overworld().getStructureManager().getOrCreate(Identifier.parse("sagadyssey_industries_ab:empty"))
                    .load(s.overworld().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[32,12,32],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            if(phase().endsWith("resume")){
                prior=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("industry-ab-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
                var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
                rules(s.overworld(),phase().startsWith("wool")?3:0);force(s.overworld());
            }
        }catch(Exception x){throw new IllegalStateException("full approved AB checkpoint required",x);}
    }
    private static void rules(ServerLevel l,int random){l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,l.getServer());l.getGameRules().set(GameRules.SPAWN_MOBS, false,l.getServer());l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, random,l.getServer());}
    private static void force(ServerLevel l){var cp=new ChunkPos(BASE);for(int x=cp.x-1;x<=cp.x+2;x++)for(int z=cp.z-1;z<=cp.z+2;z++){var p=new ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), TICKS,p,2,p,true);}}
    private static BlockPos at(int x,int y,int z){return BASE.offset(x,y,z);}
    private static void floor(GameTestHelper h,int random){var l=h.getLevel();force(l);rules(l,random);for(int x=0;x<30;x++)for(int z=0;z<24;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.STONE.defaultBlockState());}
    private static NpcBase npc(GameTestHelper h,int x,int z,NpcProfession role){var l=h.getLevel();var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(at(x,0,z).getX()+.5,BASE.getY(),at(x,0,z).getZ()+.5);n.setProfession(role);n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);n.getPersonalInventory().setItem(5,new ItemStack(Items.BREAD,8));n.setWorkZone(at(x,0,z),at(x,0,z));l.addFreshEntity(n);NpcLifeData.get(l.getServer()).ensureRegistered(n,l.getGameTime());var a=SubjectRef.character(n.getCharacterId());RightsStateData.get(l.getServer()).ensureContext(ContainerIds.npcPersonal(n.getCharacterId()),"npc:"+n.getCharacterId()+":personal",a,a,AccessPolicy.PRIVATE);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static UUID family(GameTestHelper h,String name,List<NpcBase> people){var l=h.getLevel();var s=l.getServer();var site=SettlementRegistryData.get(s).create(s,name,SettlementType.VILLAGE,l.dimension().identifier().toString(),BASE,40,"sagadyssey:kingdom",people.getFirst().getCharacterId(),l.getGameTime());var homes=HouseholdData.get(s);var home=homes.create(name,l.dimension().identifier().toString(),site.id(),people.getFirst().getCharacterId(),BASE,l.getGameTime());for(int i=1;i<people.size();i++)h.assertTrue(homes.addMember(home.id(),people.get(i).getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"same real household");return home.id();}
    private static FieldworkStorage point(GameTestHelper h,BlockPos pos,SubjectRef owner,TransferIntent intent){var l=h.getLevel();l.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var id=ProductionContainerIds.resolve(l.getServer(),l.dimension().identifier().toString(),pos);RightsStateData.get(l.getServer()).ensureContext(id,"industry-ab:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);var p=new FieldworkStorage(l.dimension().identifier().toString(),pos,id,owner,AccessPolicy.PRIVATE,intent,"industry-ab-initial-source");SupplyFlowData.get(l.getServer()).register(p);return p;}
    private static Container box(GameTestHelper h,FieldworkStorage p){return SupplyFlowService.container(h.getLevel().getServer(),p);}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static ItemFingerprint fp(String item){return new ItemFingerprint("minecraft:"+item,0);}
    private static void grant(GameTestHelper h,FieldworkStorage source,SubjectRef to,String reason){RightsStateData.get(h.getLevel().getServer()).grant(new RightGrant(IndustryTransfers.id(source.containerId()+":"+to),source.containerId(),to,Set.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),reason,h.getLevel().getGameTime(),null,true));}
    private static void hire(NpcBase worker,NpcBase owner,long now){NpcLifeData.get(MinecraftApi.server(worker)).onRecruited(worker,owner.getCharacterId(),now);}
    private static void bed(GameTestHelper h,NpcBase n,int x,int z){var l=h.getLevel();var pos=at(x,0,z);var foot=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);l.setBlockAndUpdate(pos,foot);l.setBlockAndUpdate(pos.south(),foot.setValue(BedBlock.PART,BedPart.HEAD));h.assertTrue(NpcLifeData.get(l.getServer()).assignBed(n.getCharacterId(),l.dimension().identifier().toString(),pos.south(),"finite AB test home",l.getGameTime()),"actual bed assignment");}
    private static void sprint(GameTestHelper h,int ticks){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+ticks,h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}}
    private static void save(GameTestHelper h,CompoundTag m){try{NbtIo.writeCompressed(m,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("industry-ab-checkpoint.nbt"));h.getLevel().getServer().saveEverything(true,false,true);}catch(Exception x){throw new IllegalStateException(x);}}
    private static NpcBase original(GameTestHelper h,String key){var e=h.getLevel().getEntity(prior.read(key, UUIDUtil.CODEC).orElseThrow());h.assertTrue(e instanceof NpcBase,"original "+key+" body must load, no replacement");var n=(NpcBase)e;h.assertTrue(n.getCharacterId().equals(prior.read(key+"Person", UUIDUtil.CODEC).orElseThrow()),"same "+key+" persistent person");return n;}
    private static void actor(CompoundTag m,String name,NpcBase n){m.store(name, UUIDUtil.CODEC,n.getUUID());m.store(name+"Person", UUIDUtil.CODEC,n.getCharacterId());}

    private static void woolStart(GameTestHelper h){
        floor(h,3);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var owner=npc(h,4,6,NpcProfession.WORKER);var courier=npc(h,8,4,NpcProfession.COURIER);var carpenter=npc(h,10,7,NpcProfession.WORKER);var home=family(h,"wool household",List.of(owner,courier,carpenter));
        owner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.SHEARS));hire(courier,owner,now);hire(carpenter,owner,now);bed(h,owner,4,7);bed(h,courier,7,7);bed(h,carpenter,10,7);
        for(int x=3;x<=11;x++)for(int z=3;z<=11;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
        for(int x=2;x<=12;x++)for(int z=2;z<=12;z++)if(x==2||x==12||z==2||z==12){l.setBlockAndUpdate(at(x,0,z),Blocks.OAK_FENCE.defaultBlockState());l.setBlockAndUpdate(at(x,1,z),Blocks.OAK_FENCE.defaultBlockState());}
        // Closed pasture; all real source/worker/workshop locations lie inside. No animal escape gate.
        var principal=SubjectRef.character(owner.getCharacterId());var source=point(h,at(4,0,9),principal,TransferIntent.DELIVERY);var target=point(h,at(10,0,5),principal,TransferIntent.DELIVERY);
        box(h,target).setItem(0,new ItemStack(Items.OAK_PLANKS,6));l.setBlockAndUpdate(at(10,0,4),Blocks.CRAFTING_TABLE.defaultBlockState());
        var flows=SupplyFlowData.get(s);flows.reveal(principal,source.containerId());var need=SupplyFlowService.request(s,principal,"industry:wool-for-two-beds",target,fp("white_wool"),6,now,now+48000);
        var sheep=EntityType.SHEEP.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);sheep.setPos(at(6,0,6).getX()+.5,BASE.getY(),at(6,0,6).getZ()+.5);sheep.setColor(DyeColor.WHITE);sheep.setPersistenceRequired();l.addFreshEntity(sheep);
        var sourceId=EntityResourceAuthority.registerInitialOwned(owner,sheep,"SHEAR",source,need.id(),at(2,0,2),at(12,3,12),"initial finite owned white sheep and pasture; not claiming wild sheep");
        var one=WorkshopTask.requestBed(owner,"first",target,at(10,0,4),now);var two=WorkshopTask.requestBed(owner,"second",target,at(10,0,4),now);
        SupplyFlowService.match(s,now);sprint(h,24500);
        h.succeedWhen(()->{
            var data=EntityResourceActionData.existing(s);var a=data.actions().stream().filter(x->x.read("Source", UUIDUtil.CODEC).orElseThrow().equals(sourceId)&&x.getStringOr("State", "").equals("ACTED")).findFirst().orElse(null);
            h.assertTrue(a!=null,"real native shear recorded before pickup checkpoint");h.assertTrue(sheep.isSheared(),"original sheep really shorn");
            var mark=new CompoundTag();actor(mark,"Owner",owner);actor(mark,"Courier",courier);actor(mark,"Carpenter",carpenter);mark.store("Sheep", UUIDUtil.CODEC,sheep.getUUID());mark.store("Need", UUIDUtil.CODEC,need.id());mark.store("Source", UUIDUtil.CODEC,sourceId);mark.store("Home", UUIDUtil.CODEC,home);mark.store("BedOne", UUIDUtil.CODEC,one.id());mark.store("BedTwo", UUIDUtil.CODEC,two.id());mark.put("Stock",target.save());mark.putLong("Start",now);save(h,mark);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_WOOL_CHECKPOINT tick={} person={} sheep={} action={} state=ACTED",l.getGameTime(),owner.getCharacterId(),sheep.getUUID(),a.read("Id", UUIDUtil.CODEC).orElseThrow());
        });
    }
    private static void woolResume(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("full original wool checkpoint required");var l=h.getLevel();var s=l.getServer();sprint(h,24500);
        h.succeedWhen(()->{
            var owner=original(h,"Owner");original(h,"Courier");original(h,"Carpenter");h.assertTrue(l.getEntity(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow()) instanceof Sheep,"original sheep retained");
            h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=24000,"original total wool window");
            var data=EntityResourceActionData.existing(s);h.assertTrue(data.actions().stream().noneMatch(a->a.getStringOr("State", "").equals("REVIEW")),"no unresolved native effect");
            long shears=data.actions().stream().filter(a->a.read("Source", UUIDUtil.CODEC).orElseThrow().equals(prior.read("Source", UUIDUtil.CODEC).orElseThrow())&&a.getStringOr("State", "").equals("DONE")).count();
            h.assertTrue(shears>=2,"same original sheep actually regrew and was shorn again");
            h.assertTrue(NeedRequestData.get(s).request(prior.read("Need", UUIDUtil.CODEC).orElseThrow()).status()==NeedRequestStatus.DELIVERED,"normal courier delivered six real wool");
            h.assertTrue(ProductionOrderData.get(s).byId(prior.read("BedOne", UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED
                    &&ProductionOrderData.get(s).byId(prior.read("BedTwo", UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"two original carpentry orders really worked");
            var stock=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));h.assertTrue(count(box(h,stock),Items.WHITE_BED)==2 && count(box(h,stock),Items.OAK_PLANKS)==0,"real two beds from six planks and six wool");
            h.assertTrue(owner.getMainHandItem().getDamageValue()==shears,"one actual shear-tool cost per native result, not repeated after reboot");
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_WOOL_ACCEPT tick={} person={} sheep={} shears={} woolDelivered=6 beds=2 planks=0 nativeReview=0",l.getGameTime(),owner.getCharacterId(),prior.read("Sheep", UUIDUtil.CODEC).orElseThrow(),shears);
        });
    }
    private static void fish(GameTestHelper h){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var producer=npc(h,3,5,NpcProfession.WORKER);var eater=npc(h,22,5,NpcProfession.WORKER);var courier=npc(h,14,5,NpcProfession.COURIER);family(h,"fish producer",List.of(producer));family(h,"food recipient",List.of(eater,courier));hire(courier,eater,now);
        producer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));eater.getPersonalInventory().clearContent();bed(h,producer,2,18);bed(h,eater,5,18);bed(h,courier,8,18);
        for(int x=4;x<=6;x++)for(int z=4;z<=6;z++){l.setBlockAndUpdate(at(x,-2,z),Blocks.STONE.defaultBlockState());l.setBlockAndUpdate(at(x,-1,z),Blocks.WATER.defaultBlockState());}
        var owner=SubjectRef.character(producer.getCharacterId());var recipient=SubjectRef.character(eater.getCharacterId());var source=point(h,at(11,0,5),owner,TransferIntent.GIFT);var target=point(h,at(22,0,6),recipient,TransferIntent.DELIVERY);
        grant(h,source,recipient,"producer explicitly donates actual cod to recipient");SupplyFlowData.get(s).reveal(recipient,source.containerId());
        h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,eater.getCharacterId(),l.dimension().identifier().toString(),target.position(),now),"recipient's own real food store");NpcLifeData.get(s).setHunger(eater.getCharacterId(),700,now);
        var need=SupplyFlowService.request(s,recipient,"industry:two-finite-cod-meals",target,fp("cod"),2,now,now+20000);
        var fishIds=new ArrayList<UUID>();
        for(int i=0;i<2;i++){var cod=EntityType.COD.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);cod.setPos(at(4+i, -1,5).getX()+.5,BASE.getY()-.6,at(4+i,-1,5).getZ()+.5);cod.setPersistenceRequired();l.addFreshEntity(cod);fishIds.add(cod.getUUID());EntityResourceAuthority.registerInitialOwned(producer,cod,"FISH_COD",source,need.id(),at(3,-2,3),at(7,2,7),"initial finite registered owned cod "+i);}
        SupplyFlowService.match(s,now);sprint(h,12500);
        h.succeedWhen(()->{
            var data=EntityResourceActionData.existing(s);h.assertTrue(data.actions().stream().noneMatch(a->a.getStringOr("State", "").equals("REVIEW")),"no unknown native catch");
            h.assertTrue(data.actions().stream().filter(a->a.read("Person", UUIDUtil.CODEC).orElseThrow().equals(producer.getCharacterId())&&a.getStringOr("State", "").equals("DONE")).count()==2,"two different original fish really caught and stored");
            h.assertTrue(NeedRequestData.get(s).request(need.id()).delivered()==2,"normal courier really delivered two cod");
            var needs=NpcLifeData.get(s).needs(eater.getCharacterId());h.assertTrue(needs.lastAteAt()>now && needs.hunger()<700,"recipient actually eats through original life scheduling");
            int remaining=count(box(h,source),Items.COD)+count(box(h,target),Items.COD)+producer.getPersonalInventory().count(x->x.is(Items.COD))+eater.getPersonalInventory().count(x->x.is(Items.COD))+count(courier.getTransportInventory(),Items.COD);
            h.assertTrue(remaining<2,"actual cod consumption, no unrelated bread offered to recipient");
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_FISH_ACCEPT tick={} originalFish={} producer={} recipient={} delivered=2 remainingCod={} hunger={} lastAte={} swordDamage={}",l.getGameTime(),fishIds,producer.getCharacterId(),eater.getCharacterId(),remaining,needs.hunger(),needs.lastAteAt(),producer.getMainHandItem().getDamageValue());
        });
    }
    private static void smeltStart(GameTestHelper h){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var miner=npc(h,2,5,NpcProfession.WORKER);var operator=npc(h,10,10,NpcProfession.WORKER);var smith=npc(h,18,4,NpcProfession.BLACKSMITH);var courier=npc(h,12,5,NpcProfession.COURIER);var home=family(h,"ore to replacement tool",List.of(miner,operator,smith,courier));hire(smith,operator,now);hire(courier,operator,now);
        for(var entry:List.of(new Object[]{miner,2},new Object[]{operator,5},new Object[]{smith,8},new Object[]{courier,11}))bed(h,(NpcBase)entry[0],(int)entry[1],18);
        miner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));miner.getMainHandItem().setDamageValue(miner.getMainHandItem().getMaxDamage()-5);
        miner.setWorkZone(at(3,0,5),at(8,0,5));for(int x=3;x<=5;x++)l.setBlockAndUpdate(at(x,0,5),Blocks.IRON_ORE.defaultBlockState());for(int x=6;x<=7;x++)l.setBlockAndUpdate(at(x,0,5),Blocks.COAL_ORE.defaultBlockState());l.setBlockAndUpdate(at(8,0,5),Blocks.STONE.defaultBlockState());
        var m=SubjectRef.character(miner.getCharacterId());var boss=SubjectRef.character(operator.getCharacterId());var source=point(h,at(3,0,8),m,TransferIntent.GIFT);var raw=point(h,at(10,0,5),boss,TransferIntent.DELIVERY);var tool=point(h,at(19,0,5),boss,TransferIntent.GIFT);
        h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,miner.getCharacterId(),l.dimension().identifier().toString(),source.position(),now),"original miner private store");
        FieldworkData.get(s).bindTestStorage(miner.getUUID(),source);grant(h,source,boss,"miner consents to finite cooperative raw-material gift");SupplyFlowData.get(s).reveal(boss,source.containerId());SupplyFlowData.get(s).reveal(boss,raw.containerId());
        l.setBlockAndUpdate(at(17,0,5),Blocks.ANVIL.defaultBlockState());l.setBlockAndUpdate(at(13,0,5),Blocks.FURNACE.defaultBlockState());var furnaceId=ProductionContainerIds.resolve(s,l.dimension().identifier().toString(),at(13,0,5));RightsStateData.get(s).ensureContext(furnaceId,"owned-native-furnace",boss,boss,AccessPolicy.PRIVATE);
        SupplyFlowService.request(s,boss,"industry:raw-input",raw,fp("raw_iron"),3,now,now+30000);SupplyFlowService.request(s,boss,"industry:two-different-coal-uses",raw,fp("coal"),2,now,now+30000);
        var wanted=SupplyFlowService.request(s,boss,"industry:real-replacement-tool",tool,fp("iron_pickaxe"),1,now,now+30000);SupplyFlowService.match(s,now);
        var order=FurnaceWorkAdapter.request(operator,"native-three-ingots",at(13,0,5),raw,3,now);
        var homeOwner=SubjectRef.household(home);HouseholdData.get(s).assignSharedStorage(home,l.dimension().identifier().toString(),at(24,0,5),at(24,0,5));l.setBlockAndUpdate(at(24,0,5),Blocks.BARREL.defaultBlockState());var pantry=new FieldworkStorage(l.dimension().identifier().toString(),at(24,0,5),ContainerIds.block("household_storage",l.dimension().identifier().toString(),at(24,0,5)),homeOwner,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household_supply");RightsStateData.get(s).ensureContext(pantry.containerId(),pantry.dimension()+":"+pantry.position().asLong(),homeOwner,homeOwner,AccessPolicy.HOUSEHOLD);SupplyFlowData.get(s).register(pantry);h.assertTrue(ProductionContainerIds.resolve(s,pantry.dimension(),pantry.position()).equals(pantry.containerId()),"same actual shared-store authority and HOUSEHOLD policy as normal aggregation");grant(h,pantry,m,"head's original legal household-tool access");
        sprint(h,16500);
        h.succeedWhen(()->{
            h.assertTrue(ProductionOrderData.get(s).byId(order.id()).orElseThrow().status()==ProductionOrderStatus.IN_PROGRESS,"normal operator really loaded native furnace");
            var f=(Container)l.getBlockEntity(at(13,0,5));h.assertTrue(count(f,Items.RAW_IRON)>0 && count(f,Items.IRON_INGOT)<3,"native intermediate, not completed result");
            var mark=new CompoundTag();actor(mark,"Miner",miner);actor(mark,"Operator",operator);actor(mark,"Smith",smith);actor(mark,"Courier",courier);mark.store("NativeOrder", UUIDUtil.CODEC,order.id());mark.store("ToolNeed", UUIDUtil.CODEC,wanted.id());mark.store("Home", UUIDUtil.CODEC,home);mark.put("ToolStock",tool.save());mark.put("Pantry",pantry.save());mark.putLong("Start",now);mark.putLong("Furnace",at(13,0,5).asLong());save(h,mark);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_SMELT_CHECKPOINT tick={} order={} rawInFurnace={} actualCoalSlot={} miner={} oldPickRemaining={}",l.getGameTime(),order.id(),count(f,Items.RAW_IRON),count(f,Items.COAL),miner.getCharacterId(),miner.getMainHandItem().isEmpty()?0:miner.getMainHandItem().getMaxDamage()-miner.getMainHandItem().getDamageValue());
        });
    }
    private static void smeltResume(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("original complete furnace checkpoint required");var l=h.getLevel();var s=l.getServer();UUID[] homeNeed={null};sprint(h,16500);
        h.onEachTick(()->{var tool=FieldworkStorage.load(prior.getCompoundOrEmpty("ToolStock"));if(homeNeed[0]==null && count(box(h,tool),Items.IRON_PICKAXE)==1){
            var home=SubjectRef.household(prior.read("Home", UUIDUtil.CODEC).orElseThrow());
            var demand=NeedRequestData.get(s).requests().stream().filter(r->r.key().requester().equals(home.id()) && r.key().purpose().equals("HOUSEHOLD:household:tools") && r.key().item().equals(fp("iron_pickaxe"))).findFirst().orElse(null);
            if(demand!=null){grant(h,tool,home,"original owner explicitly gifts actual finished replacement, not earlier raw materials");SupplyFlowData.get(s).reveal(home,tool.containerId());homeNeed[0]=demand.id();com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_TOOL_GIFT tick={} existingHouseholdNeed={} actualPick=1",l.getGameTime(),homeNeed[0]);}
        }});
        h.succeedWhen(()->{
            var miner=original(h,"Miner");original(h,"Operator");original(h,"Smith");original(h,"Courier");h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=16000,"unchanged original total smelting window");
            h.assertTrue(ProductionOrderData.get(s).byId(prior.read("NativeOrder", UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"real original native output delivered");
            h.assertTrue(NeedRequestData.get(s).request(prior.read("ToolNeed", UUIDUtil.CODEC).orElseThrow()).delivered()==1 && homeNeed[0]!=null && NeedRequestData.get(s).request(homeNeed[0]).delivered()==1,"actual forged tool and legal household transfer");
            var work=FieldworkData.get(s);h.assertTrue(work.receiptsFor(miner.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE).count()==6,"original miner actually mines last stone after tool replacement");
            h.assertTrue(miner.getMainHandItem().is(Items.IRON_PICKAXE) && miner.getMainHandItem().getDamageValue()==1,"new actual pickaxe worn in real subsequent work");
            var f=(Container)l.getBlockEntity(BlockPos.of(prior.getLongOr("Furnace", 0L)));h.assertTrue(count(f,Items.RAW_IRON)==0&&count(f,Items.IRON_INGOT)==0,"native furnace material not duplicated");
            var saved=l.getBlockEntity(BlockPos.of(prior.getLongOr("Furnace", 0L))).saveWithoutMetadata(l.registryAccess());
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("AB_SMELT_ACCEPT tick={} originalMiner={} nativeOrder={} rawOreMined=3 coalMined=2 nativeIngots=3 recipeIron=3 recipeCoal=1 nativeCoal=1 furnaceCoal={} burnTime={} mineReceipts=6 newPickDamage=1",l.getGameTime(),miner.getCharacterId(),prior.read("NativeOrder", UUIDUtil.CODEC).orElseThrow(),count(f,Items.COAL),saved.getIntOr("BurnTime", 0));
        });
    }
    private static void guards(GameTestHelper h){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var owner=npc(h,4,6,NpcProfession.WORKER);var other=npc(h,12,6,NpcProfession.WORKER);family(h,"guard owner",List.of(owner));family(h,"ungranted observer",List.of(other));
        owner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.SHEARS));var principal=SubjectRef.character(owner.getCharacterId());var source=point(h,at(9,0,6),principal,TransferIntent.DELIVERY);var target=point(h,at(16,0,6),principal,TransferIntent.DELIVERY);var need=SupplyFlowService.request(s,principal,"industry:guard-wool",target,fp("white_wool"),3,now,now+10000);
        var sheep=EntityType.SHEEP.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);sheep.setPos(BASE.getX()+6.5,BASE.getY(),BASE.getZ()+6.5);l.addFreshEntity(sheep);var id=EntityResourceAuthority.registerInitialOwned(owner,sheep,"SHEAR",source,need.id(),at(2,0,2),at(10,3,10),"explicit finite fixture title");
        h.assertTrue(!EntityResourceAuthority.allows(other,EntityResourceActionData.get(s).source(id),sheep),"ungranted actor cannot act on another source");
        owner.setCommand(NpcCommand.STAY);for(int i=0;i<owner.getPersonalInventory().getContainerSize();i++)if(i!=5)owner.getPersonalInventory().setItem(i,new ItemStack(Items.DIRT,64));
        h.runAtTickTime(100,()->{h.assertTrue(!sheep.isSheared()&&EntityResourceActionData.get(s).actions().isEmpty(),"no unauthorized/background effect or fabricated wool");h.succeed();});
    }
}
