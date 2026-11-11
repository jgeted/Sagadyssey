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


/** Approved completion slices. Fixtures only establish finite initial conditions. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class IndustryCompletionGameTests {
 private static final BlockPos BASE=new BlockPos(6144,80,6144);
 private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind TICKS=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("industry-completion-native");
 static CompoundTag prior;
 private static String phase(){return System.getProperty("sagadyssey.completion.phase","disabled");}
 @GameTestGenerator public static Collection<TestFunction> tests(){
  if(!Boolean.getBoolean("sagadyssey.industry.native"))return List.of();
  return switch(phase()){
   case "wood-start"->List.of(test("wood_checkpoint",IndustryCompletionGameTests::woodStart,40000));
   case "wood-resume"->List.of(test("wood_real_replenishment",IndustryCompletionGameTests::woodResume,40000));
   case "ore-start"->List.of(test("ore_checkpoint",IndustryCompletionGameTests::oreStart,24000));
   case "ore-resume"->List.of(test("ore_two_real_cycles",IndustryCompletionGameTests::oreResume,24000));
   case "crop-two"->List.of(test("native_potato_two_cycles",IndustryCompletionGameTests::potato,12000));
   case "wool-start"->List.of(test("wool_broken_tool_checkpoint",IndustryCompletionGameTests::woolStart,48000));
   case "wool-resume"->List.of(test("wool_new_need_and_new_tool",IndustryCompletionGameTests::woolResume,48000));
   case "fish-next"->List.of(test("finite_fish_new_need",IndustryCompletionGameTests::fishNext,12000));
   case "settlement-loop"->List.of(test("original_farm_feed_and_two_stone_segments",IndustryCompletionGameTests::settlementLoop,72000));
   default->List.of();};
 }
 private static TestFunction test(String n,java.util.function.Consumer<GameTestHelper> f,int ticks){return new TestFunction("industry_completion","completion."+n,"sagadyssey_industries_completion:empty",ticks,0,true,f);}
 @net.neoforged.bus.api.SubscribeEvent public static void boot(net.neoforged.neoforge.event.server.ServerStartedEvent e){
  prior=null;if(!(e.getServer() instanceof GameTestServer)||phase().equals("disabled"))return;
  try{var s=e.getServer();s.overworld().getStructureManager().getOrCreate(Identifier.parse("sagadyssey_industries_completion:empty")).load(s.overworld().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[32,12,32],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
   if(phase().endsWith("resume")){
    prior=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("industry-completion-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
    var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
    s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
    var r=saved.getCompoundOrEmpty("game_rules");if(r.getIntOr("minecraft:random_tick_speed", -1)!=((phase().startsWith("wood")||phase().startsWith("wool")||phase().startsWith("renew")||phase().startsWith("agri"))?3:0)||r.getBooleanOr("minecraft:advance_time", true)||r.getBooleanOr("minecraft:spawn_mobs", true))throw new IllegalStateException("frozen source world rules changed");
    rules(s.overworld(),(phase().startsWith("wood")||phase().startsWith("wool")||phase().startsWith("renew")||phase().startsWith("agri"))?3:0);force(s.overworld());
   }
  }catch(Exception failure){throw new IllegalStateException("original closed completion world required",failure);}
 }
    private static void rules(ServerLevel l,int random){l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,l.getServer());l.getGameRules().set(GameRules.SPAWN_MOBS, false,l.getServer());l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, random,l.getServer());}
    private static void force(ServerLevel l){var cp=new ChunkPos(BASE);for(int x=cp.x-1;x<=cp.x+2;x++)for(int z=cp.z-1;z<=cp.z+2;z++){var p=new ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), TICKS,p,2,p,true);}}
    static BlockPos at(int x,int y,int z){return BASE.offset(x,y,z);}
    static void floor(GameTestHelper h,int random){var l=h.getLevel();force(l);rules(l,random);for(int x=0;x<30;x++)for(int z=0;z<24;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.STONE.defaultBlockState());}
    static NpcBase npc(GameTestHelper h,int x,int z,NpcProfession role){var l=h.getLevel();var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(at(x,0,z).getX()+.5,BASE.getY(),at(x,0,z).getZ()+.5);n.setProfession(role);n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);n.getPersonalInventory().setItem(5,new ItemStack(Items.BREAD,8));n.setWorkZone(at(x,0,z),at(x,0,z));l.addFreshEntity(n);NpcLifeData.get(l.getServer()).ensureRegistered(n,l.getGameTime());var a=SubjectRef.character(n.getCharacterId());RightsStateData.get(l.getServer()).ensureContext(ContainerIds.npcPersonal(n.getCharacterId()),"npc:"+n.getCharacterId()+":personal",a,a,AccessPolicy.PRIVATE);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    static UUID family(GameTestHelper h,String name,List<NpcBase> people){var l=h.getLevel();var s=l.getServer();var site=SettlementRegistryData.get(s).create(s,name,SettlementType.VILLAGE,l.dimension().identifier().toString(),BASE,40,"sagadyssey:kingdom",people.getFirst().getCharacterId(),l.getGameTime());var homes=HouseholdData.get(s);var home=homes.create(name,l.dimension().identifier().toString(),site.id(),people.getFirst().getCharacterId(),BASE,l.getGameTime());for(int i=1;i<people.size();i++)h.assertTrue(homes.addMember(home.id(),people.get(i).getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"same real household");return home.id();}
    static FieldworkStorage point(GameTestHelper h,BlockPos pos,SubjectRef owner,TransferIntent intent){var l=h.getLevel();l.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var id=ProductionContainerIds.resolve(l.getServer(),l.dimension().identifier().toString(),pos);RightsStateData.get(l.getServer()).ensureContext(id,"industry-ab:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);var p=new FieldworkStorage(l.dimension().identifier().toString(),pos,id,owner,AccessPolicy.PRIVATE,intent,"industry-ab-initial-source");SupplyFlowData.get(l.getServer()).register(p);return p;}
    static Container box(GameTestHelper h,FieldworkStorage p){return SupplyFlowService.container(h.getLevel().getServer(),p);}
    static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    static ItemFingerprint fp(String item){return new ItemFingerprint("minecraft:"+item,0);}
    static void grant(GameTestHelper h,FieldworkStorage source,SubjectRef to,String reason){RightsStateData.get(h.getLevel().getServer()).grant(new RightGrant(IndustryTransfers.id(source.containerId()+":"+to),source.containerId(),to,Set.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),reason,h.getLevel().getGameTime(),null,true));}
    static void hire(NpcBase worker,NpcBase owner,long now){NpcLifeData.get(MinecraftApi.server(worker)).onRecruited(worker,owner.getCharacterId(),now);}
    static void bed(GameTestHelper h,NpcBase n,int x,int z){var l=h.getLevel();var pos=at(x,0,z);var foot=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);l.setBlockAndUpdate(pos,foot);l.setBlockAndUpdate(pos.south(),foot.setValue(BedBlock.PART,BedPart.HEAD));h.assertTrue(NpcLifeData.get(l.getServer()).assignBed(n.getCharacterId(),l.dimension().identifier().toString(),pos.south(),"finite AB test home",l.getGameTime()),"actual bed assignment");}
    static void sprint(GameTestHelper h,int ticks){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+ticks,h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}}
    static void save(GameTestHelper h,CompoundTag m){try{NbtIo.writeCompressed(m,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("industry-completion-checkpoint.nbt"));h.getLevel().getServer().saveEverything(true,false,true);}catch(Exception x){throw new IllegalStateException(x);}}
    static NpcBase original(GameTestHelper h,String key){var e=h.getLevel().getEntity(prior.read(key, UUIDUtil.CODEC).orElseThrow());h.assertTrue(e instanceof NpcBase,"original "+key+" body must load, no replacement");var n=(NpcBase)e;h.assertTrue(n.getCharacterId().equals(prior.read(key+"Person", UUIDUtil.CODEC).orElseThrow()),"same "+key+" persistent person");return n;}
    static void actor(CompoundTag m,String name,NpcBase n){m.store(name, UUIDUtil.CODEC,n.getUUID());m.store(name+"Person", UUIDUtil.CODEC,n.getCharacterId());}


 static void woodStart(GameTestHelper h){
  floor(h,3);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var worker=npc(h,3,6,NpcProfession.WORKER);var home=family(h,"finite replenishing carpentry",List.of(worker));bed(h,worker,4,18);
  var owner=SubjectRef.character(worker.getCharacterId());var stock=point(h,at(15,0,6),owner,TransferIntent.DELIVERY);var table=at(16,0,6);l.setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
  h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,worker.getCharacterId(),stock.dimension(),stock.position(),now),"real original private stock");FieldworkData.get(s).bindTestStorage(worker.getUUID(),stock);
  var root=at(7,0,6);for(int x=3;x<=11;x++)for(int z=2;z<=10;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
  for(int y=0;y<3;y++)l.setBlockAndUpdate(root.above(y),Blocks.OAK_LOG.defaultBlockState());for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.setBlockAndUpdate(root.offset(x,3,z),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE,1));
  worker.setWorkZone(at(3,0,2),at(11,9,10));worker.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_AXE));worker.getPersonalInventory().setItem(0,new ItemStack(Items.OAK_SAPLING,1));worker.getPersonalInventory().setItem(1,new ItemStack(Items.DIRT,32));
  var one=WorkshopTask.requestPlanks(worker,"planks-first",stock,table,now);var two=WorkshopTask.requestPlanks(worker,"planks-next",stock,table,now);var three=WorkshopTask.requestSticks(worker,"sticks-first",stock,table,now);var four=WorkshopTask.requestSticks(worker,"sticks-next",stock,table,now);SupplyFlowData.get(s).reveal(owner,stock.containerId());
  sprint(h,40500);
  h.succeedWhen(()->{
   h.assertTrue(FieldworkData.get(s).receiptsFor(worker.getUUID()).stream().anyMatch(r->r.action().operation()==FieldworkAction.Operation.PLANT&&r.action().target().equals(root))&&l.getBlockState(root).is(Blocks.OAK_SAPLING),"original seed really planted before full restart");
   var mark=new CompoundTag();actor(mark,"Worker",worker);mark.store("Home", UUIDUtil.CODEC,home);mark.putLong("Root",root.asLong());mark.putLong("Start",now);mark.put("Stock",stock.save());mark.store("One", UUIDUtil.CODEC,one.id());mark.store("Two", UUIDUtil.CODEC,two.id());mark.store("Three", UUIDUtil.CODEC,three.id());mark.store("Four", UUIDUtil.CODEC,four.id());save(h,mark);
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_WOOD_CHECKPOINT tick={} person={} seedInitial=1 initialLogs=3 orders=4",l.getGameTime(),worker.getCharacterId());
  });
 }
 private static void woodResume(GameTestHelper h){
  if(prior==null)throw new IllegalStateException("whole world checkpoint required");var l=h.getLevel();var s=l.getServer();sprint(h,40500);
  h.succeedWhen(()->{
   var n=original(h,"Worker");var root=BlockPos.of(prior.getLongOr("Root", 0L));h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=40000,"frozen whole-run 40000 ticks");
   var receipts=FieldworkData.get(s).receiptsFor(n.getUUID());long planted=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT&&r.action().target().equals(root)).count();long chopped=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.CHOP&&r.action().target().equals(root)).count();
   h.assertTrue(planted>=2&&chopped>=3,"two actual native tree regrowths, not initial stumps; plants="+planted+" chops="+chopped);
   int producedSeeds=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().startsWith("minecraft:oak_sapling")&&d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();h.assertTrue(producedSeeds>=1,"new actual seed supplies second planting");
   for(String key:List.of("One","Two","Three","Four")){var o=ProductionOrderData.get(s).byId(prior.read(key, UUIDUtil.CODEC).orElseThrow()).orElseThrow();h.assertTrue(o.status()==ProductionOrderStatus.COMPLETED&&o.assignedWorkerId().equals(n.getCharacterId()),"four original orders worked by original resident: "+key);}
   var stock=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));var b=box(h,stock);h.assertTrue(count(b,Items.OAK_PLANKS)==20&&count(b,Items.STICK)==8,"24 actual planks, four consumed into eight sticks");
   int produced=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().startsWith("minecraft:oak_log")&&d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();h.assertTrue(count(b,Items.OAK_LOG)+n.getPersonalInventory().count(i->i.is(Items.OAK_LOG))+6==produced,"all actual logs located or consumed, six real logs used");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_WOOD_ACCEPT tick={} person={} roots={} plants={} seeds={} producedLogs={} consumedLogs=6 planks=20 sticks=8 orders=4",l.getGameTime(),n.getCharacterId(),chopped,planted,producedSeeds,produced);
  });
 }

 private static void oreStart(GameTestHelper h){
  floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var miner=npc(h,2,5,NpcProfession.WORKER);var operator=npc(h,11,8,NpcProfession.WORKER);var smith=npc(h,20,4,NpcProfession.BLACKSMITH);var courier=npc(h,12,4,NpcProfession.COURIER);var home=family(h,"two cold native mineral cycles",List.of(miner,operator,smith,courier));hire(courier,operator,now);
  for(var e:List.of(new Object[]{miner,2},new Object[]{operator,5},new Object[]{smith,8},new Object[]{courier,11}))bed(h,(NpcBase)e[0],(int)e[1],18);
  var m=SubjectRef.character(miner.getCharacterId());var boss=SubjectRef.character(operator.getCharacterId());var household=SubjectRef.household(home);
  miner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));miner.getMainHandItem().setDamageValue(miner.getMainHandItem().getMaxDamage()-5);miner.setWorkZone(at(3,0,5),at(13,0,5));
  for(int x:List.of(3,4,5,8,9,10))l.setBlockAndUpdate(at(x,0,5),Blocks.IRON_ORE.defaultBlockState());for(int x:List.of(6,7,11,12))l.setBlockAndUpdate(at(x,0,5),Blocks.COAL_ORE.defaultBlockState());l.setBlockAndUpdate(at(13,0,5),Blocks.STONE.defaultBlockState());
  var source=point(h,at(3,0,8),m,TransferIntent.GIFT);var raw=point(h,at(10,0,9),boss,TransferIntent.DELIVERY);var output=point(h,at(16,0,9),boss,TransferIntent.GIFT);
  h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,miner.getCharacterId(),source.dimension(),source.position(),now),"original miner source");FieldworkData.get(s).bindTestStorage(miner.getUUID(),source);grant(h,source,boss,"original miner gifts finite actual extraction, no fixtures later");grant(h,output,household,"actual output is gift to household, input buffer remains private");
  var flows=SupplyFlowData.get(s);flows.reveal(boss,source.containerId());flows.reveal(boss,raw.containerId());flows.reveal(boss,output.containerId());flows.reveal(household,output.containerId());
  flows.policy(new SupplyFlowData.Policy("native-raw",boss,raw.containerId(),fp("raw_iron"),3,"workshop"));flows.policy(new SupplyFlowData.Policy("native-coal",boss,raw.containerId(),fp("coal"),2,"workshop"));flows.policy(new SupplyFlowData.Policy("native-product",boss,output.containerId(),fp("iron_ingot"),3,"workshop"));flows.policy(new SupplyFlowData.Policy("recipe-coal",boss,output.containerId(),fp("coal"),1,"workshop"));
  l.setBlockAndUpdate(at(13,0,9),Blocks.FURNACE.defaultBlockState());var furnaceId=ProductionContainerIds.resolve(s,raw.dimension(),at(13,0,9));RightsStateData.get(s).ensureContext(furnaceId,"original cooperative furnace",boss,boss,AccessPolicy.PRIVATE);
  var pantryPos=at(23,0,8);l.setBlockAndUpdate(pantryPos,Blocks.BARREL.defaultBlockState());HouseholdData.get(s).assignSharedStorage(home,raw.dimension(),pantryPos,pantryPos);var pantry=new FieldworkStorage(raw.dimension(),pantryPos,ContainerIds.block("household_storage",raw.dimension(),pantryPos),household,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household_supply");RightsStateData.get(s).ensureContext(pantry.containerId(),pantry.dimension()+":"+pantryPos.asLong(),household,household,AccessPolicy.HOUSEHOLD);flows.register(pantry);grant(h,pantry,m,"original household member tool access");l.setBlockAndUpdate(at(21,0,8),Blocks.ANVIL.defaultBlockState());
  SupplyFlowService.aggregate(s,now);SupplyFlowService.match(s,now);sprint(h,24500);
  h.succeedWhen(()->{
   var nativeOrder=ProductionOrderData.get(s).assignedTo(operator.getCharacterId()).stream().filter(FurnaceWorkAdapter::recognized).filter(o->o.status()==ProductionOrderStatus.IN_PROGRESS).findFirst().orElse(null);h.assertTrue(nativeOrder!=null,"formal demand caused actual raw/coal loading, no fixture native request");
   var toolNeed=NeedRequestData.get(s).requests().stream().filter(r->r.key().requester().equals(home)&&r.key().purpose().equals("HOUSEHOLD:household:tools")).findFirst().orElseThrow();h.assertTrue(toolNeed.requested()==2,"unchanged actual four-member household demand two picks");
   var mark=new CompoundTag();actor(mark,"Miner",miner);actor(mark,"Operator",operator);actor(mark,"Smith",smith);actor(mark,"Courier",courier);mark.store("Home", UUIDUtil.CODEC,home);mark.store("FirstNative", UUIDUtil.CODEC,nativeOrder.id());mark.store("ToolNeed", UUIDUtil.CODEC,toolNeed.id());mark.putLong("Start",now);mark.put("Source",source.save());mark.put("Raw",raw.save());mark.put("Output",output.save());mark.put("Pantry",pantry.save());mark.putLong("Furnace",at(13,0,9).asLong());save(h,mark);
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_ORE_CHECKPOINT tick={} native={} toolNeed={} physicalMines={}",l.getGameTime(),nativeOrder.id(),toolNeed.id(),FieldworkData.get(s).receiptsFor(miner.getUUID()).size());
  });
 }
 private static void oreResume(GameTestHelper h){
  if(prior==null)throw new IllegalStateException("whole cold ore world required");var l=h.getLevel();var s=l.getServer();sprint(h,24500);
  h.onEachTick(()->{if(l.getGameTime()%2000==0)com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_ORE_TRACE tick={} orders={} needs={}",l.getGameTime(),ProductionOrderData.get(s).ordersForWorker(prior.read("OperatorPerson", UUIDUtil.CODEC).orElseThrow()),NeedRequestData.get(s).requests());});
  h.succeedWhen(()->{
   var miner=original(h,"Miner");var operator=original(h,"Operator");original(h,"Smith");original(h,"Courier");h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=24000,"two-cycle total bound, including pre-restart");
   var toolNeed=NeedRequestData.get(s).request(prior.read("ToolNeed", UUIDUtil.CODEC).orElseThrow());h.assertTrue(toolNeed.delivered()==2,"two actual singly forged picks fulfill original demand");
   var finished=ProductionOrderData.get(s).ordersForWorker(operator.getCharacterId()).stream().filter(FurnaceWorkAdapter::recognized).filter(o->o.assignedWorkerId().equals(operator.getCharacterId())&&o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(finished.size()==2,"two actual automatic native cycles from six actually mined ore");
   h.assertTrue(finished.stream().allMatch(o->FurnaceWorkAdapter.completedDelivery(s,o)),"four confirmed physical handoffs for each cycle");
   var mines=FieldworkData.get(s).receiptsFor(miner.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE).toList();h.assertTrue(mines.size()>=11,"original miner obtained tool and really mined new ore/coal and final stone; actual="+mines.size());
   int worn=miner.getPersonalInventory().count(i->i.is(Items.IRON_PICKAXE)&&i.getDamageValue()>=6)+(miner.getMainHandItem().is(Items.IRON_PICKAXE)&&miner.getMainHandItem().getDamageValue()>=6?1:0);h.assertTrue(worn>=1,"replacement tool actually used by original miner");
   var furnace=(Container)l.getBlockEntity(BlockPos.of(prior.getLongOr("Furnace", 0L)));int residualCoal=count(furnace,Items.COAL);for(String key:List.of("Source","Raw","Output","Pantry"))residualCoal+=count(box(h,FieldworkStorage.load(prior.getCompoundOrEmpty(key))),Items.COAL);
   for(String key:List.of("Miner","Operator","Smith","Courier")){var n=original(h,key);residualCoal+=n.getPersonalInventory().count(i->i.is(Items.COAL))+count(n.getTransportInventory(),Items.COAL);}
   h.assertTrue(residualCoal>=0&&residualCoal<=1,"four actual coal pieces, two forging uses, one/two native fuel burns; no fuel refund");
   h.assertTrue(NeedRequestData.get(s).requests().stream().noneMatch(r->r.detail().contains("核对")&&r.reserved()>0),"no hidden unresolved committed material");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_ORE_ACCEPT tick={} originalMiner={} nativeCycles=2 actualIngots=6 originalToolDemand=2 mineReceipts={} residualCoal={} noFixtureReplenishment=true",l.getGameTime(),miner.getCharacterId(),mines.size(),residualCoal);
  });
 }

 private static void potato(GameTestHelper h){
  floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var cook=npc(h,4,5,NpcProfession.WORKER);var courier=npc(h,8,5,NpcProfession.COURIER);var eater=npc(h,17,5,NpcProfession.WORKER);family(h,"native food producer",List.of(cook));family(h,"real food recipient",List.of(eater,courier));hire(courier,eater,now);eater.setCommand(NpcCommand.STAY);eater.getPersonalInventory().clearContent();NpcLifeData.get(s).setHunger(eater.getCharacterId(),700,now);
  var owner=SubjectRef.character(cook.getCharacterId());var consumer=SubjectRef.character(eater.getCharacterId());var raw=point(h,at(5,0,8),owner,TransferIntent.DELIVERY);var output=point(h,at(10,0,8),owner,TransferIntent.GIFT);var target=point(h,at(17,0,8),consumer,TransferIntent.DELIVERY);h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,eater.getCharacterId(),target.dimension(),target.position(),now),"actual recipient store");
  box(h,raw).setItem(0,new ItemStack(Items.POTATO,6));box(h,raw).setItem(1,new ItemStack(Items.COAL,2));grant(h,output,consumer,"initial producer consent for actual cooked food");var flows=SupplyFlowData.get(s);flows.reveal(owner,raw.containerId());flows.reveal(owner,output.containerId());flows.reveal(consumer,output.containerId());flows.policy(new SupplyFlowData.Policy("raw-food",owner,raw.containerId(),fp("potato"),3,"workshop"));flows.policy(new SupplyFlowData.Policy("cooked-food",owner,output.containerId(),fp("baked_potato"),3,"workshop"));
  l.setBlockAndUpdate(at(7,0,8),Blocks.FURNACE.defaultBlockState());var machine=ProductionContainerIds.resolve(s,raw.dimension(),at(7,0,8));RightsStateData.get(s).ensureContext(machine,"original food machine",owner,owner,AccessPolicy.PRIVATE);
  var need=SupplyFlowService.request(s,consumer,"industry:real-cooked-meal",target,fp("baked_potato"),6,now,now+12000);SupplyFlowService.aggregate(s,now);SupplyFlowService.match(s,now);sprint(h,12500);
  h.succeedWhen(()->{
   var orders=ProductionOrderData.get(s).ordersForWorker(cook.getCharacterId()).stream().filter(o->o.recipeId().equals(FurnaceWorkAdapter.BOUND_POTATO)&&o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(orders.size()==2,"two demand-driven real native cooking cycles");h.assertTrue(orders.stream().allMatch(o->FurnaceWorkAdapter.completedDelivery(s,o)),"native food identities and actual transfers confirmed");
   h.assertTrue(NeedRequestData.get(s).request(need.id()).delivered()==6,"six actual cooked potatoes delivered by original courier");
   var life=NpcLifeData.get(s).needs(eater.getCharacterId());h.assertTrue(life.lastAteAt()>=now,"recipient actually ate after physical arrival");int left=count(box(h,raw),Items.POTATO)+count(box(h,output),Items.POTATO)+cook.getPersonalInventory().count(i->i.is(Items.POTATO));h.assertTrue(left==0,"six initial raw potatoes actually consumed into native cooking");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_POTATO_ACCEPT tick={} cook={} eater={} rawInitial=6 coalInitial=2 nativeCycles=2 delivered=6 lastAteAt={} physicalRemainingFood={} ",l.getGameTime(),cook.getCharacterId(),eater.getCharacterId(),life.lastAteAt(),count(box(h,target),Items.BAKED_POTATO));
  });
 }

 private static void woolStart(GameTestHelper h){
  floor(h,3);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var owner=npc(h,4,6,NpcProfession.WORKER);var courier=npc(h,8,4,NpcProfession.COURIER);var carpenter=npc(h,10,7,NpcProfession.WORKER);var smith=npc(h,9,8,NpcProfession.BLACKSMITH);var home=family(h,"wool demand and real replacement tool",List.of(owner,courier,carpenter,smith));hire(courier,owner,now);hire(carpenter,owner,now);hire(smith,owner,now);
  owner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.SHEARS));owner.getMainHandItem().setDamageValue(owner.getMainHandItem().getMaxDamage()-1);bed(h,owner,4,7);bed(h,courier,7,7);bed(h,carpenter,10,7);bed(h,smith,9,9);
  for(int x=3;x<=11;x++)for(int z=3;z<=11;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());for(int x=2;x<=12;x++)for(int z=2;z<=12;z++)if(x==2||x==12||z==2||z==12){l.setBlockAndUpdate(at(x,0,z),Blocks.OAK_FENCE.defaultBlockState());l.setBlockAndUpdate(at(x,1,z),Blocks.OAK_FENCE.defaultBlockState());}
  var principal=SubjectRef.character(owner.getCharacterId());var source=point(h,at(4,0,9),principal,TransferIntent.DELIVERY);var target=point(h,at(10,0,5),principal,TransferIntent.DELIVERY);box(h,source).setItem(0,new ItemStack(Items.IRON_INGOT,2));box(h,target).setItem(0,new ItemStack(Items.OAK_PLANKS,6));l.setBlockAndUpdate(at(10,0,4),Blocks.CRAFTING_TABLE.defaultBlockState());l.setBlockAndUpdate(at(8,0,9),Blocks.ANVIL.defaultBlockState());
  h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,owner.getCharacterId(),source.dimension(),source.position(),now),"original owner tool/output store");var flows=SupplyFlowData.get(s);flows.reveal(principal,source.containerId());flows.reveal(principal,target.containerId());flows.policy(new SupplyFlowData.Policy("real-wool-buffer",principal,target.containerId(),fp("white_wool"),3,"workshop"));SupplyFlowService.aggregate(s,now);
  var need=NeedRequestData.get(s).requests().stream().filter(r->r.key().purpose().equals("CHARACTER:workshop:real-wool-buffer")).findFirst().orElseThrow();
  var sheep=EntityType.SHEEP.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);sheep.setPos(at(6,0,6).getX()+.5,BASE.getY(),at(6,0,6).getZ()+.5);sheep.setColor(DyeColor.WHITE);sheep.setPersistenceRequired();l.addFreshEntity(sheep);var sourceId=EntityResourceAuthority.registerInitialOwned(owner,sheep,"SHEAR",source,need.id(),at(2,0,2),at(12,3,12),"same initial owned white sheep, finite pasture, no wild appropriation");
  var one=WorkshopTask.requestBed(owner,"new-need-first-bed",target,at(10,0,4),now);var two=WorkshopTask.requestBed(owner,"new-need-next-bed",target,at(10,0,4),now);SupplyFlowService.match(s,now);sprint(h,48500);
  h.succeedWhen(()->{
   var a=EntityResourceActionData.existing(s).actions().stream().filter(x->x.read("Source", UUIDUtil.CODEC).orElseThrow().equals(sourceId)&&x.getStringOr("State", "").equals("ACTED")).findFirst().orElse(null);h.assertTrue(a!=null&&sheep.isSheared()&&owner.getMainHandItem().isEmpty(),"original shear actually broke while producing first native output before restart");
   var mark=new CompoundTag();actor(mark,"Owner",owner);actor(mark,"Courier",courier);actor(mark,"Carpenter",carpenter);actor(mark,"Smith",smith);mark.store("Home", UUIDUtil.CODEC,home);mark.store("Sheep", UUIDUtil.CODEC,sheep.getUUID());mark.store("Need", UUIDUtil.CODEC,need.id());mark.store("Source", UUIDUtil.CODEC,sourceId);mark.store("BedOne", UUIDUtil.CODEC,one.id());mark.store("BedTwo", UUIDUtil.CODEC,two.id());mark.put("Stock",target.save());mark.put("Output",source.save());mark.putLong("Start",now);save(h,mark);
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_WOOL_CHECKPOINT tick={} person={} sheep={} oldShearsBroken=true action={}",l.getGameTime(),owner.getCharacterId(),sheep.getUUID(),a.read("Id", UUIDUtil.CODEC).orElseThrow());
  });
 }
 private static void woolResume(GameTestHelper h){
  if(prior==null)throw new IllegalStateException("same complete wool world required");var l=h.getLevel();var s=l.getServer();sprint(h,48500);
  h.succeedWhen(()->{
   var owner=original(h,"Owner");original(h,"Courier");original(h,"Carpenter");var smith=original(h,"Smith");h.assertTrue(l.getEntity(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow()) instanceof Sheep,"same original sheep loaded");h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=48000,"original total wool tool/new-need bound");
   var data=EntityResourceActionData.existing(s);h.assertTrue(data.actions().stream().noneMatch(a->a.getStringOr("State", "").equals("REVIEW")),"no uncertain native result");
   h.assertTrue(data.sources().stream().filter(x->x.read("Target", UUIDUtil.CODEC).orElseThrow().equals(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow())).map(x->x.read("Need", UUIDUtil.CODEC).orElseThrow()).distinct().count()>=2,"same owned sheep formally bound to a NEW real policy need");
   h.assertTrue(NeedRequestData.get(s).request(prior.read("Need", UUIDUtil.CODEC).orElseThrow()).delivered()==3,"original three-wool demand retained");
   for(String key:List.of("BedOne","BedTwo"))h.assertTrue(ProductionOrderData.get(s).byId(prior.read(key, UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"two real beds consumed six actual wool and planks");
   var forged=ProductionOrderData.get(s).ordersForWorker(smith.getCharacterId()).stream().filter(o->o.recipeId().equals("minecraft:shears")&&o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(forged.size()==1,"one replacement shear really forged from finite two iron");h.assertTrue(owner.getMainHandItem().is(Items.SHEARS)&&owner.getMainHandItem().getDamageValue()>0,"original owner legitimately acquired and used replacement shears");
   var target=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));var output=FieldworkStorage.load(prior.getCompoundOrEmpty("Output"));h.assertTrue(count(box(h,target),Items.WHITE_BED)==2&&count(box(h,target),Items.OAK_PLANKS)==0&&count(box(h,output),Items.IRON_INGOT)==0,"real beds and real replacement inputs");
   int produced=data.actions().stream().flatMap(a->MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND).stream()).map(x->(CompoundTag)x).filter(x->x.getCompoundOrEmpty("Item").getStringOr("Item", "").equals("minecraft:white_wool")).mapToInt(x->x.getIntOr("Quantity", 0)).sum();int physical=count(box(h,target),Items.WHITE_WOOL)+count(box(h,output),Items.WHITE_WOOL);
   for(String key:List.of("Owner","Courier","Carpenter","Smith")){var n=original(h,key);physical+=n.getPersonalInventory().count(i->i.is(Items.WHITE_WOOL))+count(n.getTransportInventory(),Items.WHITE_WOOL);}h.assertTrue(produced==physical+6,"native wool equals locations plus six real bed inputs");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_WOOL_ACCEPT tick={} person={} sheep={} toolForge={} producedWool={} consumedWool=6 physicalWool={} beds=2 newNeed=true",l.getGameTime(),owner.getCharacterId(),prior.read("Sheep", UUIDUtil.CODEC).orElseThrow(),forged.getFirst().id(),produced,physical);
  });
 }
 private static void fishNext(GameTestHelper h){
  floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var producer=npc(h,3,5,NpcProfession.WORKER);var eater=npc(h,22,5,NpcProfession.WORKER);var courier=npc(h,14,5,NpcProfession.COURIER);family(h,"finite fish original producer",List.of(producer));family(h,"fish recipient",List.of(eater,courier));hire(courier,eater,now);producer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));eater.getPersonalInventory().clearContent();NpcLifeData.get(s).setHunger(eater.getCharacterId(),900,now);bed(h,producer,2,18);bed(h,eater,5,18);bed(h,courier,8,18);
  for(int x=4;x<=6;x++)for(int z=4;z<=6;z++){l.setBlockAndUpdate(at(x,-2,z),Blocks.STONE.defaultBlockState());l.setBlockAndUpdate(at(x,-1,z),Blocks.WATER.defaultBlockState());}
  var owner=SubjectRef.character(producer.getCharacterId());var recipient=SubjectRef.character(eater.getCharacterId());var source=point(h,at(11,0,5),owner,TransferIntent.GIFT);var target=point(h,at(22,0,6),recipient,TransferIntent.DELIVERY);grant(h,source,recipient,"original finite owned fish gift");var flows=SupplyFlowData.get(s);flows.reveal(recipient,source.containerId());h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,eater.getCharacterId(),target.dimension(),target.position(),now),"same actual food store");flows.policy(new SupplyFlowData.Policy("fish-meal-buffer",recipient,target.containerId(),fp("cod"),1,"household"));SupplyFlowService.aggregate(s,now);var first=NeedRequestData.get(s).requests().stream().filter(r->r.key().purpose().equals("CHARACTER:household:fish-meal-buffer")).findFirst().orElseThrow();
  var fishIds=new ArrayList<UUID>();for(int i=0;i<2;i++){var cod=EntityType.COD.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);cod.setPos(at(4+i,-1,5).getX()+.5,BASE.getY()-.6,at(4+i,-1,5).getZ()+.5);cod.setPersistenceRequired();l.addFreshEntity(cod);fishIds.add(cod.getUUID());EntityResourceAuthority.registerInitialOwned(producer,cod,"FISH_COD",source,first.id(),at(3,-2,3),at(7,2,7),"initial explicitly owned existing cod "+i);}
  SupplyFlowService.match(s,now);sprint(h,12500);
  h.succeedWhen(()->{
   var needs=NeedRequestData.get(s).requests().stream().filter(r->r.key().purpose().equals("CHARACTER:household:fish-meal-buffer")).toList();h.assertTrue(needs.stream().filter(r->r.delivered()==1).count()>=2,"two distinct real buffer demands each receive one original fish");h.assertTrue(NeedRequestData.get(s).request(first.id()).delivered()==1,"original demand not overwritten");var data=EntityResourceActionData.existing(s);h.assertTrue(data.actions().stream().noneMatch(a->a.getStringOr("State", "").equals("REVIEW")),"no ambiguous native result");h.assertTrue(data.actions().stream().filter(a->a.getStringOr("State", "").equals("DONE")).count()==2,"two finite original fish caught exactly once");h.assertTrue(data.sources().stream().map(x->x.read("Need", UUIDUtil.CODEC).orElseThrow()).distinct().count()>=2,"next fish bound from original title, not spawned");h.assertTrue(NpcLifeData.get(s).needs(eater.getCharacterId()).lastAteAt()>now,"actual first fish consumed causes next real shortage");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_FISH_ACCEPT tick={} fish={} needs={} originalNeed={} firstMeal={} sourceExhausted=true",l.getGameTime(),fishIds,needs.size(),first.id(),NpcLifeData.get(s).needs(eater.getCharacterId()).lastAteAt());
  });
 }

 private static void settlementLoop(GameTestHelper h){
  var l=h.getLevel();var s=l.getServer();s.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
  var b=com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests.fixture(h,0,com.jgeted.sagadyssey.social.growth.GrowthLayout.COORDINATED);
  long start=l.getGameTime();boolean[] enabled={false};long[] firstSegment={-1},secondSegment={-1};sprint(h,72500);
  h.onEachTick(()->{
   var growth=com.jgeted.sagadyssey.social.growth.SettlementGrowthData.existing(s);var row=growth==null?null:growth.site(b.buildingKey());if(row==null)return;
   if(!enabled[0]){
    var official=com.jgeted.sagadyssey.social.politics.PoliticalStateData.get(s).titleHolder(b.settlementId());
    String result=com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.get(s).activate(s,b.buildingKey(),official,l.getGameTime());
    if(result.equals("ACTIVATED")||result.equals("ALREADY_ACTIVE")){enabled[0]=true;com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_SETTLEMENT_INITIAL tick={} template={} source={} growth={}",l.getGameTime(),b.templateId(),b.buildingKey(),row);}
   }
   var data=com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.existing(s);var coord=data==null?null:data.site(b.buildingKey());if(coord==null)return;
   int placed=coord.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("HOUSE").getIntOr("Placed", 0);
   if(firstSegment[0]<0&&placed>=10){firstSegment[0]=l.getGameTime();com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_STONE_SEGMENT one=10 tick={} coordinator={}",l.getGameTime(),coord);}
   if(secondSegment[0]<0&&placed>=20){secondSegment[0]=l.getGameTime();com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_STONE_SEGMENT two=20 tick={} coordinator={}",l.getGameTime(),coord);}
  });
  h.succeedWhen(()->{
   h.assertTrue(enabled[0],"formal existing household/public-work activation succeeded");h.assertTrue(l.getGameTime()-start<=72000,"frozen one-settlement total window");
   var farmer=com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests.actor(l,b,0);var courier=com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests.actor(l,b,1);var worker=com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests.actor(l,b,2);h.assertTrue(farmer!=null&&courier!=null&&worker!=null,"original three real residents remain");
   var coord=com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.get(s).site(b.buildingKey());h.assertTrue(coord!=null,"same real project exists");var house=coord.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("HOUSE");h.assertTrue(!house.getStringOr("WallMaterial", "").equals("WOOD"),"this frozen stone branch needs actual quarried material; wood alternative is not stone proof");h.assertTrue(firstSegment[0]>=0&&secondSegment[0]>firstSegment[0]&&house.getIntOr("Placed", 0)>=20,"two actual ten-block segments of original project, no reset");
   for(int i=0;i<20;i++)for(var pos:com.jgeted.sagadyssey.social.growth.GrowthLayout.house(b).get(i).positions())h.assertTrue(l.getBlockState(pos).is(Blocks.COBBLESTONE),"real constructed stone exists at original project position");
   var work=FieldworkData.get(s);int stone=work.receiptsFor(worker.getUUID()).stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().startsWith("minecraft:cobblestone")&&d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();h.assertTrue(stone>=20,"at least20 real quarried stone entered original labor/material chain");
   var crops=new HashMap<BlockPos,Integer>();for(var r:work.receiptsFor(farmer.getUUID()))if(r.action().operation()==FieldworkAction.Operation.HARVEST)crops.merge(r.action().target(),1,Integer::sum);h.assertTrue(crops.values().stream().anyMatch(n->n>=2),"same farm cell truly harvested twice after natural growth, no mature injection");
   h.assertTrue(NpcLifeData.get(s).needs(farmer.getCharacterId()).lastAteAt()>start&&NpcLifeData.get(s).needs(worker.getCharacterId()).lastAteAt()>start,"original producer residents actually eat");
   var g=com.jgeted.sagadyssey.social.growth.SettlementGrowthData.get(s).site(b.buildingKey());h.assertTrue(g.getIntOr("ConsumedFeed", 0)>=2&&MinecraftApi.listOfType(g, "Animals",Tag.TAG_COMPOUND).size()>=3,"real food consumed to original pigs and actual native offspring registered");
   var mark=new CompoundTag();mark.put("Village",com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.get(s).village(b.buildingKey()));mark.put("Coordinator",coord);mark.put("Growth",g);mark.putLong("Start",start);mark.putLong("FirstSegment",firstSegment[0]);mark.putLong("SecondSegment",secondSegment[0]);mark.putInt("ActualStoneProduced",stone);save(h,mark);
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("COMPLETION_SETTLEMENT_ACCEPT tick={} originalPeople=3 realStone={} segment1={} segment2={} repeatedCropCells={} feedConsumed={} pigs={}",l.getGameTime(),stone,firstSegment[0],secondSegment[0],crops.values().stream().filter(n->n>=2).count(),g.getIntOr("ConsumedFeed", 0),MinecraftApi.listOfType(g, "Animals",Tag.TAG_COMPOUND).size());
  });
 }

}
