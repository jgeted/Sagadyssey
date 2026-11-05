package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
/** Trade fixtures move existing starting food; forest timber must come from actual fieldwork. */
public final class RegionalExchangeGameTests {
 private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
 public static void run(GameTestHelper h,boolean obstruction){var l=h.getLevel();var server=l.getServer();var seller=NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2);var buyer=NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2);int[] stage={0},last={-1},waiting={0},hungry={0};var walls=new ArrayList<BlockPos>();UUID[] deal={null};
  h.onEachTick(()->{if(last[0]==server.getTickCount())return;last[0]=server.getTickCount();var data=SettlementExchangeData.get(server);
   if(server.getTickCount()%1000==0)Sagadyssey.LOGGER.info("R2_EXCHANGE_PROGRESS stage={} deals={}",stage[0],data.deals().stream().map(t->t.getString("State")+":"+t.getString("Reason")).toList());
   if(stage[0]==0&&h.getTick()>=500){
    var forest=(Container)l.getBlockEntity(NaturalVillageLayout.at(buyer,NaturalVillageLayout.WOOD));if(count(forest,Items.OAK_LOG)<2)return;
    for(var b:List.of(seller,buyer))for(int i:new int[]{0,2})NaturalVillageR1GameTests.actor(l,b,i).setNoAi(true);
    var farmer=NaturalVillageR1GameTests.actor(l,seller,0);var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(seller,NaturalVillageLayout.PANTRY));var rights=RightsStateData.get(server);var bag=ContainerIds.npcPersonal(farmer.getCharacterId());var store=NaturalVillageEconomyData.storeId(seller,1);long now=l.getGameTime();
    var request=new TransferRequest(UUID.randomUUID(),SubjectRef.character(farmer.getCharacterId()),TransferIntent.GIFT,List.of(new TransferLeg(bag,store,new ItemFingerprint("minecraft:carrot",0),16)),List.of(new ExpectedRevision(bag,rights.context(bag).revision()),new ExpectedRevision(store,rights.context(store).revision())),"controlled barter fixture moves the real initial reserve",now);
    h.assertTrue(new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(bag,new TransferEndpoint(bag,farmer.getPersonalInventory()),store,new TransferEndpoint(store,pantry)),now).successful(),"fixture reuses sixteen generated carrots, not a new grant");
    NpcLifeData.get(server).setHunger(NaturalVillageLifeData.person(buyer,1),850,now);stage[0]=1;
   }
   if(stage[0]==1){var t=data.deals().stream().filter(r->r.getCompound("Buyer").getString("Template").equals(NaturalVillageLayout.WOOD_V2.toString())).findFirst().orElse(null);if(t==null||!t.hasUUID("PayRoute"))return;deal[0]=t.getUUID("Id");var route=SupplyFlowData.get(server).route(t.getUUID("PayRoute"));if(route.stage()!=SupplyFlowData.Stage.CARRYING)return;
    var n=NaturalVillageR1GameTests.actor(l,buyer,1);h.assertTrue(n!=null&&count(n.getTransportInventory(),Items.OAK_LOG)==2,"buyer really carries the two prepaid logs");NaturalVillageR1GameTests.visiting(l,seller,false);NaturalVillageR1GameTests.visiting(l,buyer,false);stage[0]=2;
   }
   if(stage[0]>=2&&stage[0]<5){var t=data.deals().stream().filter(r->r.getUUID("Id").equals(deal[0])).findFirst().orElseThrow();boolean destinationFailure=System.getProperty("sagadyssey.society.phase","").equals("r2-exchange-destination");
    if(destinationFailure&&stage[0]==4){if(!t.getString("State").equals("REVIEW"))return;var n=NaturalVillageR1GameTests.actor(l,buyer,1);var food=SupplyFlowData.get(server).route(t.getUUID("FoodRoute"));h.assertTrue(n!=null&&count(n.getTransportInventory(),Items.CARROT)==4&&NeedRequestData.get(server).request(food.need()).delivered()==0&&t.getInt("FoodOwed")==4,"changed receiver keeps physical cargo and prepaid debt, never invents delivery");Sagadyssey.LOGGER.info("R2_EXCHANGE_DESTINATION_ACCEPT originalCargo=4 delivered=0 unpaidDebt=4 state=REVIEW");h.succeed();stage[0]=5;return;}
    h.assertTrue(!t.getString("State").equals("REVIEW"),"exchange waits safely: "+t.getString("Reason"));
    if(destinationFailure&&stage[0]==2&&t.hasUUID("FoodRoute")&&SupplyFlowData.get(server).route(t.getUUID("FoodRoute")).stage()==SupplyFlowData.Stage.CARRYING){var head=NaturalVillageLifeData.person(buyer,1);h.assertTrue(!com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.get(server).request(server,buyer,seller,head).equals("ACCEPTED"),"migration cannot abandon paid or in-transit exchange");com.jgeted.sagadyssey.social.household.HouseholdData.get(server).assignSharedStorage(NaturalVillageLifeData.home(buyer),buyer.dimension(),NaturalVillageLayout.at(buyer,NaturalVillageLayout.ARRIVAL_PANTRY),NaturalVillageLayout.at(buyer,NaturalVillageLayout.ARRIVAL_PANTRY));stage[0]=4;return;}

    if(System.getProperty("sagadyssey.society.phase","").equals("r2-exchange-save")&&t.hasUUID("FoodRoute")&&SupplyFlowData.get(server).route(t.getUUID("FoodRoute")).stage()==SupplyFlowData.Stage.CARRYING){
     var checkpoint=t.copy();checkpoint.putLong("Time",l.getGameTime());try{net.minecraft.nbt.NbtIo.writeCompressed(checkpoint,server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("regional-exchange-checkpoint.nbt"));server.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception x){throw new IllegalStateException(x);}Sagadyssey.LOGGER.info("R2_EXCHANGE_SAVE_ACCEPT debt={} courier={}",t.getInt("FoodOwed"),NaturalVillageLifeData.person(buyer,1));h.succeed();stage[0]=5;return;
    }
    if(obstruction&&stage[0]==2&&t.hasUUID("FoodRoute")){var route=SupplyFlowData.get(server).route(t.getUUID("FoodRoute"));if(route.stage()==SupplyFlowData.Stage.CARRYING){var n=NaturalVillageR1GameTests.actor(l,buyer,1);h.assertTrue(n!=null&&count(n.getTransportInventory(),Items.CARROT)==4&&t.getInt("FoodOwed")==4,"paid obligation remains unsettled while actual food is in cargo");var c=n.blockPosition();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=3;y++)if(Math.abs(x)==2||Math.abs(z)==2||y==3){var p=c.offset(x,y,z);if(l.getBlockState(p).isAir()){walls.add(p);l.setBlockAndUpdate(p,Blocks.BEDROCK.defaultBlockState());}}waiting[0]=server.getTickCount();hungry[0]=NpcLifeData.get(server).needs(n.getCharacterId()).hunger();stage[0]=3;}}
    if(stage[0]==3&&server.getTickCount()-waiting[0]>=2000){var route=SupplyFlowData.get(server).route(t.getUUID("FoodRoute"));var n=NaturalVillageR1GameTests.actor(l,buyer,1);h.assertTrue(route.stage()==SupplyFlowData.Stage.CARRYING&&n!=null&&count(n.getTransportInventory(),Items.CARROT)==4&&NpcLifeData.get(server).needs(n.getCharacterId()).hunger()>hungry[0],"blocked trade keeps goods in the same cargo and hunger grows without fictitious delivery");for(var p:walls)l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());stage[0]=4;}
    if(t.getString("State").equals("DONE")){
     h.assertTrue(!obstruction||stage[0]==4,"obstructed trade must observe failure then restore the road");var pay=SupplyFlowData.get(server).route(t.getUUID("PayRoute"));var food=SupplyFlowData.get(server).route(t.getUUID("FoodRoute"));
     h.assertTrue(pay.stage()==SupplyFlowData.Stage.DONE&&food.stage()==SupplyFlowData.Stage.DONE&&NeedRequestData.get(server).request(pay.need()).delivered()==2&&NeedRequestData.get(server).request(food.need()).delivered()==4&&t.getInt("FoodOwed")==0,"both exact deliveries settle the original prepaid contract");
     var source=(Container)l.getBlockEntity(NaturalVillageLayout.at(seller,NaturalVillageLayout.PANTRY));if(source!=null)h.assertTrue(count(source,Items.OAK_LOG)==2&&count(source,Items.CARROT)==12,"seller actually receives timber and pays food");
     var n=NaturalVillageR1GameTests.actor(l,buyer,1);h.assertTrue(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(buyer,1))&&count(n.getTransportInventory(),Items.OAK_LOG)==0&&count(n.getTransportInventory(),Items.CARROT)==0,"one original courier completes the actual round trip");
     var saved=SettlementExchangeData.load(data.save(new CompoundTag(),server.registryAccess()),server.registryAccess());h.assertTrue(saved.deals().stream().anyMatch(r->r.getUUID("Id").equals(deal[0])&&r.getString("State").equals("DONE")),"reload retains settlement and does not create a new reward or debt");Sagadyssey.LOGGER.info("R2_EXCHANGE_ACCEPT blocked={} logs=2 carrots=4 courier={} paidDebt=0 metrics={}",obstruction,n.getCharacterId(),LodMetrics.snapshot(server,LodMetrics.Kind.REGION_TRADE));h.succeed();stage[0]=5;
    }
   }
  });
 }
 public static void restart(GameTestHelper h){var l=h.getLevel();var server=l.getServer();CompoundTag saved;
  try{saved=net.minecraft.nbt.NbtIo.readCompressed(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("regional-exchange-checkpoint.nbt"),net.minecraft.nbt.NbtAccounter.unlimitedHeap());}catch(Exception e){throw new IllegalStateException(e);}
  var buyer=NaturalVillageLifeData.decode(saved.getCompound("Buyer"));UUID id=saved.getUUID("Id");int[] last={-1};
  h.onEachTick(()->{if(last[0]==server.getTickCount())return;last[0]=server.getTickCount();var t=SettlementExchangeData.get(server).deals().stream().filter(d->d.getUUID("Id").equals(id)).findFirst().orElseThrow();h.assertTrue(!t.getString("State").equals("REVIEW"),"reloaded route preserves its obligation: "+t.getString("Reason"));
   if(!t.getString("State").equals("DONE"))return;var pay=SupplyFlowData.get(server).route(t.getUUID("PayRoute"));var food=SupplyFlowData.get(server).route(t.getUUID("FoodRoute"));var n=NaturalVillageR1GameTests.actor(l,buyer,1);
   h.assertTrue(t.getInt("FoodOwed")==0&&n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(buyer,1))&&count(n.getTransportInventory(),Items.CARROT)==0&&NeedRequestData.get(server).request(pay.need()).delivered()==2&&NeedRequestData.get(server).request(food.need()).delivered()==4,"same paid contract and original cargo settle once after actual JVM restart");Sagadyssey.LOGGER.info("R2_EXCHANGE_RESTART_ACCEPT original={} deliveredLogs=2 deliveredCarrots=4 debt=0",n.getCharacterId());h.succeed();
  });
 }

}
