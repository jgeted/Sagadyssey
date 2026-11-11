package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.*;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import java.util.*;

/** Controlled boundary fixtures; mature-block changes here do not count as natural-growth evidence. */
public final class NaturalVillageBoundaryGameTests {
    private static NpcBase actor(ServerLevel l,NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);}
    private static int count(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))n+=c.getItem(i).getCount();return n;}
    private static int stock(ServerLevel l,NaturalVillageBuilding b){int n=0;for(var p:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC))n+=count((Container)l.getBlockEntity(NaturalVillageLayout.at(b,p)));for(int i=0;i<3;i++)n+=count(actor(l,b,i).getPersonalInventory())+count(actor(l,b,i).getTransportInventory());return n;}
    private static boolean away(ServerLevel l,NaturalVillageBuilding b){var d=NaturalVillageEconomyData.get(l.getServer());var r=d.row(b.buildingKey());if(r==null||!r.getStringOr("Phase", "").equals("REMOTE"))return false;for(int i=0;i<3;i++)if(actor(l,b,i)!=null)return false;return l.getChunkSource().getChunkNow(b.buildingOrigin().getX()>>4,b.buildingOrigin().getZ()>>4)==null;}
    private static boolean returned(ServerLevel l,NaturalVillageBuilding b){var r=NaturalVillageEconomyData.get(l.getServer()).row(b.buildingKey());if(r.getStringOr("Phase", "").equals("REVIEW"))throw new net.minecraft.gametest.framework.GameTestAssertException(net.minecraft.network.chat.Component.literal(r.getStringOr("Reason", "")), -1);if(!r.getStringOr("Phase", "").equals("NEAR"))return false;for(int i=0;i<3;i++)if(actor(l,b,i)==null)return false;return true;}
    private static <T extends NbtSavedData> void read(MinecraftServer s,String name,NbtSavedData.Factory<T> factory){try{var storage=s.overworld().getDataStorage();var root=storage.readTagFromDisk(name,null,net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());storage.set(factory.type(name),factory.deserializer().apply(root.getCompoundOrEmpty("data"),s.registryAccess()));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void diskReadback(MinecraftServer s){s.saveEverything(true,true,true);read(s,RightsStateData.DATA_NAME,RightsStateData.FACTORY);read(s,TransferLedgerData.DATA_NAME,TransferLedgerData.FACTORY);read(s,NeedRequestData.NAME,NeedRequestData.FACTORY);read(s,SupplyFlowData.NAME,SupplyFlowData.FACTORY);read(s,NpcLodData.NAME,NpcLodData.FACTORY);read(s,NaturalVillageEconomyData.NAME,NaturalVillageEconomyData.FACTORY);}
    public static void cargo(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var b=NaturalVillageR1GameTests.fixture(h,0);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0,s);int[] stage={0},boundary={0},last={-1};UUID[] routeId={null};CompoundTag[] checkpoint={null};
        h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var d=NaturalVillageEconomyData.get(s);
            if(stage[0]==0&&h.getTick()>=300){actor(l,b,0).setNoAi(true);actor(l,b,2).setNoAi(true);var raw=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));var bag=actor(l,b,0).getPersonalInventory();for(int i=0;i<bag.getContainerSize();i++)if(bag.getItem(i).is(Items.CARROT))raw.setItem(0,bag.removeItemNoUpdate(i));stage[0]=1;}
            if(stage[0]==1){var route=routeId[0]==null?SupplyFlowData.get(s).routes().stream().filter(r->r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(b)))).findFirst().orElse(null):SupplyFlowData.get(s).route(routeId[0]);if(route==null)return;routeId[0]=route.id();
                var expected=boundary[0]==0?SupplyFlowData.Stage.HELD:boundary[0]==1?SupplyFlowData.Stage.CARRYING:SupplyFlowData.Stage.DONE;if(route.stage()!=expected)return;
                var need=NeedRequestData.get(s).request(route.need());var t=new CompoundTag();t.putString("Stage",route.stage().name());t.putInt("Reserved",need.reserved());t.putInt("Transit",need.inTransit());t.putInt("Delivered",need.delivered());t.putInt("Cargo",count(actor(l,b,1).getTransportInventory()));checkpoint[0]=t;actor(l,b,1).setNoAi(true);NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=2;
            }
            if(stage[0]==2&&away(l,b)){s.getWorldData().overworldData().setGameTime(l.getGameTime()+400);l.setDayTime(l.getDayTime()+400);stage[0]=3;}
            if(stage[0]==3){if(d.backlog(l.getGameTime())>0)return;diskReadback(s);NaturalVillageR1GameTests.visiting(l,b,true);stage[0]=4;}
            if(stage[0]==4&&returned(l,b)){var route=SupplyFlowData.get(s).route(routeId[0]);var need=NeedRequestData.get(s).request(route.need());var t=checkpoint[0];h.assertTrue(route.stage().name().equals(t.getStringOr("Stage", ""))&&need.reserved()==t.getIntOr("Reserved", 0)&&need.inTransit()==t.getIntOr("Transit", 0)&&need.delivered()==t.getIntOr("Delivered", 0),"original reservation/cargo/delivery fact survives actual unload and disk readback");
                h.assertTrue(count(actor(l,b,1).getTransportInventory())==t.getIntOr("Cargo", 0)&&stock(l,b)==16,"same real cargo; no replayed pickup or delivery");
                for(int i=0;i<3;i++)h.assertTrue(actor(l,b,i).getCharacterId().equals(NaturalVillageLifeData.person(b,i))&&NpcLodRuntime.loaded(actor(l,b,i)),"same original people and valid inventory checkpoint");
                Sagadyssey.LOGGER.info("R2_CARGO_BOUNDARY_PASS boundary={} stage={} cargo={} delivered={}",boundary[0],route.stage(),t.getIntOr("Cargo", 0),need.delivered());actor(l,b,1).setNoAi(false);
                if(++boundary[0]==3){h.assertTrue(need.delivered()==16&&route.stage()==SupplyFlowData.Stage.DONE,"exactly one final delivery");Sagadyssey.LOGGER.info("R2_CARGO_ACCEPT three boundaries stock=16 delivered=16");h.succeed();stage[0]=5;}else stage[0]=1;
            }
        });
    }
    private static long harvests(ServerLevel l,NaturalVillageBuilding b){return FieldworkData.get(l.getServer()).receiptsFor(NaturalVillageLifeData.body(b,0)).stream().filter(r->r.action().operation()==FieldworkAction.Operation.HARVEST).count();}
    private static int nearDelta(ServerLevel l,NaturalVillageBuilding b){return FieldworkData.get(l.getServer()).receiptsFor(NaturalVillageLifeData.body(b,0)).stream().flatMap(r->r.inventoryDeltas().stream()).filter(i->i.fingerprint().equals("minecraft:carrot#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();}
    private static long nearSeeds(ServerLevel l,NaturalVillageBuilding b){return FieldworkData.get(l.getServer()).receiptsFor(NaturalVillageLifeData.body(b,0)).stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT).count();}
    private static void installHarvest(GameTestHelper h,NaturalVillageBuilding b){var l=h.getLevel();var n=actor(l,b,0);n.setNoAi(true);NpcBrainRuntime.cancel(n,PlanCancellationReason.MANUAL,"controlled boundary fixture selects one real harvest plan");var p=NaturalVillageLayout.at(b,NaturalVillageLayout.crops().getFirst());l.setBlockAndUpdate(p,Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,7));var target=FieldworkDiscovery.inspect(n,p);var path=FieldworkRoutes.plan(n,p,false);h.assertTrue(target!=null&&path!=null,"actual existing fieldwork discovery and safe route");n.getNavigation().stop();n.snapTo(path.stand().getX()+.5,path.stand().getY(),path.stand().getZ()+.5,0,0);
        NpcBrainRuntime.data(l.getServer()).save(FieldworkPlanFactory.work(n.getUUID(),target,path,b.dimension(),l.getGameTime()));n.setNoAi(false);}
    public static void crop(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var b=NaturalVillageR1GameTests.fixture(h,0);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0,s);int[] stage={0},last={-1},wait={0};long[] remoteSeed={0};
        h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var d=NaturalVillageEconomyData.get(s);
            if(s.getTickCount()%1000==0)Sagadyssey.LOGGER.info("R2_CROP_PROGRESS stage={} harvests={} reason={}",stage[0],harvests(l,b),d.row(b.buildingKey())==null?"initializing":d.row(b.buildingKey()).getStringOr("Reason", ""));
            if(stage[0]==0&&h.getTick()>=300){actor(l,b,1).setNoAi(true);actor(l,b,2).setNoAi(true);installHarvest(h,b);h.assertTrue(harvests(l,b)==0&&d.handoff(s,b.buildingKey(),null,null),"pre-harvest boundary established without a committed physical action");NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=1;}
            if((stage[0]==1||stage[0]==6)&&away(l,b)){s.getWorldData().overworldData().setGameTime(l.getGameTime()+400);l.setDayTime(l.getDayTime()+400);stage[0]++;}
            if(stage[0]==2||stage[0]==7){if(d.backlog(l.getGameTime())>0)return;var c=RemoteCropClock.load(d.row(b.buildingKey()).getCompoundOrEmpty("Clock"));if(stage[0]==2)h.assertTrue(c.produced()==4&&c.planted()==1,"pre-harvest ownership produces and replants exactly once remotely");else{h.assertTrue(c.produced()==0&&c.planted()<=1,"committed physical harvest never becomes a remote harvest again");remoteSeed[0]=c.planted();}diskReadback(s);NaturalVillageR1GameTests.visiting(l,b,true);stage[0]++;}
            if((stage[0]==3||stage[0]==8)&&returned(l,b)){actor(l,b,0).setNoAi(false);wait[0]=s.getTickCount();stage[0]++;}
            if(stage[0]==4&&s.getTickCount()-wait[0]>=200){h.assertTrue(harvests(l,b)==0&&stock(l,b)==19,"old uncommitted harvest cannot replay against the already replanted crop");installHarvest(h,b);stage[0]=5;}
            if(stage[0]==5&&harvests(l,b)==1){var p=NaturalVillageLayout.at(b,NaturalVillageLayout.crops().getFirst());h.assertTrue(l.getBlockState(p).isAir()&&nearSeeds(l,b)==0,"post-harvest checkpoint precedes physical planting");h.assertTrue(d.handoff(s,b.buildingKey(),null,null),"post-commit boundary uses final real inventory and block");NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=6;}
            if(stage[0]==9&&s.getTickCount()-wait[0]>=200){h.assertTrue(harvests(l,b)==1&&nearSeeds(l,b)+remoteSeed[0]==1,"one physical or remote replant, with no duplicate seed input");h.assertTrue(19+nearDelta(l,b)-remoteSeed[0]==stock(l,b),"receipted physical and remote crop changes conserve actual stock");Sagadyssey.LOGGER.info("R2_CROP_ACCEPT harvests=1 nearSeeds={} remoteSeeds={} stock={}",nearSeeds(l,b),remoteSeed[0],stock(l,b));h.succeed();stage[0]=10;}
        });
    }
    private static int virtual(ServerLevel l,CompoundTag row){int n=0;for(var e:MinecraftApi.listOfType(row, "Stores",Tag.TAG_COMPOUND))n+=count(NaturalVillageEconomyData.inventory(MinecraftApi.listOfType(((CompoundTag)e), "Items",Tag.TAG_COMPOUND),l.registryAccess()));return n;}
    public static void interrupted(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var b=NaturalVillageR1GameTests.fixture(h,0);int[] state={0},last={-1};
        h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var d=NaturalVillageEconomyData.get(s);
            if(state[0]==0&&h.getTick()>=300){NaturalVillageR1GameTests.visiting(l,b,false);state[0]=1;}
            if(state[0]==1&&away(l,b)){s.getWorldData().overworldData().setGameTime(l.getGameTime()+45000);l.setDayTime(l.getDayTime()+45000);state[0]=2;}
            if(state[0]==2){var row=d.row(b.buildingKey());if(!row.contains("Batch"))return;var root=d.save(new CompoundTag(),s.registryAccess());var changed=MinecraftApi.listOfType(root, "Regions",Tag.TAG_COMPOUND).getCompoundOrEmpty(0);var batch=changed.getCompoundOrEmpty("Batch");long at=RemoteCropClock.load(changed.getCompoundOrEmpty("Clock")).settledAt()+200;batch.putBoolean("Submitting",true);batch.putLong("Due",at);int before=virtual(l,changed);UUID tx=batch.read("Id", UUIDUtil.CODEC).orElseThrow();s.overworld().getDataStorage().set(NaturalVillageEconomyData.FACTORY.type(NaturalVillageEconomyData.NAME),NaturalVillageEconomyData.load(root,s.registryAccess()));d=NaturalVillageEconomyData.get(s);d.advance(s,b.buildingKey(),at,1);var after=d.row(b.buildingKey());
                h.assertTrue(after.getStringOr("Phase", "").equals("REVIEW")&&TransferLedgerData.get(s).receipt(tx)==null,"unknown interrupted transfer is paused, not resubmitted");h.assertTrue(virtual(l,after)==before&&d.advance(s,b.buildingKey(),at+10000,64)==0,"goods and pending progress stay intact during review");Sagadyssey.LOGGER.info("R2_INTERRUPT_ACCEPT virtualStock={} transaction={}",before,tx);h.succeed();state[0]=3;}
        });
    }
}
